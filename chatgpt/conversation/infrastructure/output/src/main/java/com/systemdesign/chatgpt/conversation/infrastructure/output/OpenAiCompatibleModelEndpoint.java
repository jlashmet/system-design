package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.ModelEndpoint;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class OpenAiCompatibleModelEndpoint implements ModelEndpoint {
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String MODELS_PATH = "/v1/models";

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
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(messages, false)))
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
        Objects.requireNonNull(deltaConsumer, "deltaConsumer");
        HttpRequest request = requestBuilder(chatCompletionsUri)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(messages, true)))
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
            try (Stream<String> lines = response.body()) {
                lines.filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring("data:".length()).trim())
                        .filter(data -> !data.isEmpty() && !"[DONE]".equals(data))
                        .forEach(data -> appendDelta(data, model, content, deltaConsumer));
            }
            if (content.isEmpty()) throw new IllegalStateException("model stream did not contain assistant content");
            return new Completion(model[0], content.toString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("model stream interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("model stream failed", exception);
        }
    }

    private void appendDelta(String data, String[] model, StringBuilder content, Consumer<String> deltaConsumer) {
        try {
            JsonNode root = objectMapper.readTree(data);
            if (root.hasNonNull("model") && !root.path("model").asText().isBlank()) model[0] = root.path("model").asText();
            JsonNode delta = root.path("choices").path(0).path("delta").path("content");
            if (!delta.isTextual() || delta.asText().isEmpty()) return;
            String text = delta.asText();
            content.append(text);
            deltaConsumer.accept(text);
        } catch (IOException exception) {
            throw new IllegalStateException("invalid model stream event", exception);
        }
    }

    private String requestBody(List<Message> messages, boolean stream) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) throw new IllegalArgumentException("messages must not be empty");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", profile.model());
        root.put("stream", stream);
        ArrayNode requestMessages = root.putArray("messages");
        for (Message message : messages) {
            ObjectNode requestMessage = requestMessages.addObject();
            requestMessage.put("role", message.role().name().toLowerCase());
            requestMessage.put("content", message.content());
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (IOException exception) {
            throw new IllegalStateException("could not serialize model request", exception);
        }
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
}
