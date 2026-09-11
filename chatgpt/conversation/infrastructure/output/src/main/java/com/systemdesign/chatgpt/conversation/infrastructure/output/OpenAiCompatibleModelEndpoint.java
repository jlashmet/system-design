package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelEndpoint;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;
import com.systemdesign.chatgpt.conversation.domain.ToolCall;
import com.systemdesign.chatgpt.conversation.domain.ToolCallTranscript;
import com.systemdesign.chatgpt.conversation.domain.ToolDefinition;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class OpenAiCompatibleModelEndpoint implements ModelEndpoint {
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String MODELS_PATH = "/v1/models";
    private static final String TOOL_CALL_ID_PREFIX = "tool_call_id=";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI chatCompletionsUri;
    private final URI modelsUri;
    private final String apiKey;
    private final ModelProfile profile;
    private final Duration requestTimeout;

    public OpenAiCompatibleModelEndpoint(HttpClient httpClient, ObjectMapper objectMapper, URI baseUri,
            String apiKey, ModelProfile profile, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(baseUri, "baseUri");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.profile = Objects.requireNonNull(profile, "profile");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        String base = baseUri.toString().replaceAll("/+$", "");
        this.chatCompletionsUri = URI.create(base + CHAT_COMPLETIONS_PATH);
        this.modelsUri = URI.create(base + MODELS_PATH);
    }

    @Override
    public ModelProfile profile() {
        return profile;
    }

    @Override
    public boolean healthy() {
        HttpRequest request = requestBuilder(modelsUri).GET().build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    @Override
    public Completion complete(List<Message> messages) {
        HttpRequest request = requestBuilder(chatCompletionsUri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(messages, false, List.of())))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            requireSuccess(response.statusCode(), response.body());
            JsonNode root = objectMapper.readTree(response.body());
            String model = root.path("model").asText(profile.model());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) throw new IllegalStateException("model response did not contain assistant content");
            return new Completion(model, content);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("model request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("model request failed", exception);
        }
    }

    @Override
    public Completion stream(List<Message> messages, Consumer<String> deltaConsumer) {
        TurnResult result = streamTurn(messages, Set.of(), List.of(), deltaConsumer);
        if (result instanceof FinalResponse finalResponse) return finalResponse.completion();
        throw new IllegalStateException("model requested a tool during text-only completion");
    }

    @Override
    public TurnResult streamTurn(List<Message> messages, Set<ModelCapability> requiredCapabilities,
            List<ToolDefinition> tools, Consumer<String> deltaConsumer) {
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(deltaConsumer, "deltaConsumer");
        HttpRequest request = requestBuilder(chatCompletionsUri)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(messages, true, tools)))
                .build();
        try {
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body;
                try (Stream<String> lines = response.body()) { body = String.join("\n", lines.toList()); }
                requireSuccess(response.statusCode(), body);
            }
            StringBuilder content = new StringBuilder();
            String[] model = {profile.model()};
            Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();
            try (Stream<String> lines = response.body()) {
                lines.filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring("data:".length()).trim())
                        .filter(data -> !data.isEmpty() && !"[DONE]".equals(data))
                        .forEach(data -> appendDelta(data, model, content, toolCalls, deltaConsumer));
            }
            if (!toolCalls.isEmpty()) {
                return new ToolRequests(model[0], toolCalls.values().stream().map(this::toToolCall).toList());
            }
            if (content.isEmpty()) throw new IllegalStateException("model stream did not contain assistant content or tool calls");
            return new FinalResponse(new Completion(model[0], content.toString()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("model stream interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("model stream failed", exception);
        }
    }

    private void appendDelta(String data, String[] model, StringBuilder content,
            Map<Integer, ToolCallAccumulator> toolCalls, Consumer<String> deltaConsumer) {
        try {
            JsonNode root = objectMapper.readTree(data);
            if (root.hasNonNull("model") && !root.path("model").asText().isBlank()) model[0] = root.path("model").asText();
            JsonNode delta = root.path("choices").path(0).path("delta");
            JsonNode text = delta.path("content");
            if (text.isTextual() && !text.asText().isEmpty()) {
                content.append(text.asText());
                deltaConsumer.accept(text.asText());
            }
            for (JsonNode toolCallDelta : delta.path("tool_calls")) {
                int index = toolCallDelta.path("index").asInt(0);
                ToolCallAccumulator accumulator = toolCalls.computeIfAbsent(index, ignored -> new ToolCallAccumulator());
                if (toolCallDelta.hasNonNull("id")) accumulator.providerId = toolCallDelta.path("id").asText();
                JsonNode function = toolCallDelta.path("function");
                if (function.hasNonNull("name")) accumulator.name.append(function.path("name").asText());
                if (function.hasNonNull("arguments")) accumulator.arguments.append(function.path("arguments").asText());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("invalid model stream event", exception);
        }
    }

    private ToolCall toToolCall(ToolCallAccumulator accumulator) {
        if (accumulator.providerId == null || accumulator.providerId.isBlank()) {
            throw new IllegalStateException("provider tool call did not contain an id");
        }
        if (accumulator.name.isEmpty()) throw new IllegalStateException("provider tool call did not contain a function name");
        UUID id = normalizedToolCallId(accumulator.providerId);
        return new ToolCall(id, accumulator.name.toString(), parseArguments(accumulator.arguments.toString()));
    }

    private UUID normalizedToolCallId(String providerId) {
        try {
            return UUID.fromString(providerId);
        } catch (IllegalArgumentException ignored) {
            return UUID.nameUUIDFromBytes(providerId.getBytes(StandardCharsets.UTF_8));
        }
    }

    private Map<String, ToolCall.Value> parseArguments(String argumentsJson) {
        try {
            JsonNode root = objectMapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            if (!root.isObject()) throw new IllegalStateException("tool arguments must be a JSON object");
            Map<String, ToolCall.Value> arguments = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> arguments.put(entry.getKey(), toToolValue(entry.getValue())));
            return Map.copyOf(arguments);
        } catch (IOException exception) {
            throw new IllegalStateException("provider returned invalid tool arguments", exception);
        }
    }

    private ToolCall.Value toToolValue(JsonNode value) {
        if (value.isTextual()) return new ToolCall.StringValue(value.asText());
        if (value.isIntegralNumber()) return new ToolCall.IntegerValue(value.longValue());
        if (value.isFloatingPointNumber()) return new ToolCall.NumberValue(value.doubleValue());
        if (value.isBoolean()) return new ToolCall.BooleanValue(value.booleanValue());
        throw new IllegalStateException("tool arguments only support primitive string, integer, number, and boolean values");
    }

    private String requestBody(List<Message> messages, boolean stream, List<ToolDefinition> tools) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(tools, "tools");
        if (messages.isEmpty()) throw new IllegalArgumentException("messages must not be empty");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", profile.model());
        root.put("stream", stream);
        ArrayNode requestMessages = root.putArray("messages");
        for (Message message : messages) appendProviderMessage(requestMessages, message);
        if (!tools.isEmpty()) {
            ArrayNode requestTools = root.putArray("tools");
            for (ToolDefinition tool : tools) appendToolDefinition(requestTools, tool);
            root.put("tool_choice", "auto");
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (IOException exception) {
            throw new IllegalStateException("could not serialize model request", exception);
        }
    }

    private void appendProviderMessage(ArrayNode requestMessages, Message message) {
        if (message.role() == MessageRole.ASSISTANT) {
            List<ToolCall> storedRequests = ToolCallTranscript.parse(message.content());
            if (!storedRequests.isEmpty()) {
                ObjectNode providerMessage = requestMessages.addObject();
                providerMessage.put("role", "assistant");
                providerMessage.putNull("content");
                ArrayNode calls = providerMessage.putArray("tool_calls");
                for (ToolCall stored : storedRequests) {
                    ObjectNode call = calls.addObject();
                    call.put("id", stored.id().toString());
                    call.put("type", "function");
                    ObjectNode function = call.putObject("function");
                    function.put("name", stored.name());
                    function.put("arguments", toolArgumentsJson(stored.arguments()));
                }
                return;
            }
        }
        if (message.role() == MessageRole.TOOL) {
            UUID callId = parseStoredToolCallId(message.content());
            if (callId != null) {
                ObjectNode providerMessage = requestMessages.addObject();
                providerMessage.put("role", "tool");
                providerMessage.put("tool_call_id", callId.toString());
                providerMessage.put("content", message.content());
                return;
            }
        }
        ObjectNode providerMessage = requestMessages.addObject();
        providerMessage.put("role", message.role().name().toLowerCase());
        providerMessage.put("content", message.content());
    }

    private String toolArgumentsJson(Map<String, ToolCall.Value> arguments) {
        ObjectNode root = objectMapper.createObjectNode();
        arguments.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ToolCall.Value value = entry.getValue();
            if (value instanceof ToolCall.StringValue string) root.put(entry.getKey(), string.value());
            else if (value instanceof ToolCall.IntegerValue integer) root.put(entry.getKey(), integer.value());
            else if (value instanceof ToolCall.NumberValue number) root.put(entry.getKey(), number.value());
            else if (value instanceof ToolCall.BooleanValue bool) root.put(entry.getKey(), bool.value());
            else throw new IllegalArgumentException("unsupported tool argument value: " + value.getClass().getName());
        });
        try {
            return objectMapper.writeValueAsString(root);
        } catch (IOException exception) {
            throw new IllegalStateException("could not serialize persisted tool arguments", exception);
        }
    }

    private UUID parseStoredToolCallId(String content) {
        for (String line : content.split("\\R")) {
            if (!line.startsWith(TOOL_CALL_ID_PREFIX)) continue;
            try {
                return UUID.fromString(line.substring(TOOL_CALL_ID_PREFIX.length()));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private void appendToolDefinition(ArrayNode requestTools, ToolDefinition tool) {
        ObjectNode providerTool = requestTools.addObject();
        providerTool.put("type", "function");
        ObjectNode function = providerTool.putObject("function");
        function.put("name", tool.name());
        function.put("description", tool.description());
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");
        for (ToolDefinition.Parameter parameter : tool.parameters()) {
            ObjectNode property = properties.putObject(parameter.name());
            property.put("type", jsonType(parameter.type()));
            property.put("description", parameter.description());
            if (parameter.required()) required.add(parameter.name());
        }
        parameters.put("additionalProperties", false);
    }

    private String jsonType(ToolDefinition.Type type) {
        return switch (type) {
            case STRING -> "string";
            case INTEGER -> "integer";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
        };
    }

    private HttpRequest.Builder requestBuilder(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout);
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        return builder;
    }

    private void requireSuccess(int statusCode, String body) {
        if (statusCode >= 200 && statusCode < 300) return;
        String detail = body == null ? "" : body.strip();
        if (detail.length() > 500) detail = detail.substring(0, 500);
        throw new IllegalStateException("model provider returned HTTP " + statusCode + (detail.isEmpty() ? "" : ": " + detail));
    }

    private static final class ToolCallAccumulator {
        private String providerId;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
