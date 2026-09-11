package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway.Completion;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway.FinalResponse;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway.ToolRequests;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway.TurnResult;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;
import com.systemdesign.chatgpt.conversation.domain.ToolCall;
import com.systemdesign.chatgpt.conversation.domain.ToolDefinition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleModelEndpointTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void completesStreamsAndChecksHealthAgainstCompatibleEndpoints() throws Exception {
        List<String> requestBodies = new ArrayList<>();
        List<String> authorizationHeaders = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/models", exchange -> respond(exchange, 200, "{\"data\":[]}", "application/json"));
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean streaming = requestBodies.getLast().contains("\"stream\":true");
            if (streaming) {
                String events = "data: {\"model\":\"provider-model\",\"choices\":[{\"delta\":{\"content\":\"hello \"}}]}\n\n"
                        + "data: {\"model\":\"provider-model\",\"choices\":[{\"delta\":{\"content\":\"world\"}}]}\n\n"
                        + "data: [DONE]\n\n";
                respond(exchange, 200, events, "text/event-stream");
            } else {
                respond(exchange, 200,
                        "{\"model\":\"provider-model\",\"choices\":[{\"message\":{\"content\":\"full answer\"}}]}",
                        "application/json");
            }
        });
        server.start();

        ModelProfile profile = new ModelProfile("configured-model",
                Set.of(ModelCapability.TEXT_GENERATION, ModelCapability.STREAMING), 10, 20);
        OpenAiCompatibleModelEndpoint endpoint = endpoint(profile);
        Message user = new Message(UUID.randomUUID(), MessageRole.USER, "hello", Instant.now());

        assertThat(endpoint.healthy()).isTrue();
        assertThat(endpoint.complete(List.of(user))).isEqualTo(new Completion("provider-model", "full answer"));
        List<String> deltas = new ArrayList<>();
        assertThat(endpoint.stream(List.of(user), deltas::add))
                .isEqualTo(new Completion("provider-model", "hello world"));

        assertThat(deltas).containsExactly("hello ", "world");
        assertThat(authorizationHeaders).containsOnly("Bearer test-key");
        assertThat(requestBodies).allSatisfy(body -> {
            assertThat(body).contains("\"model\":\"configured-model\"");
            assertThat(body).contains("\"role\":\"user\"");
            assertThat(body).contains("\"content\":\"hello\"");
        });
    }

    @Test
    void mapsToolsParsesStreamingToolCallsAndReconstructsContinuationProtocol() throws Exception {
        List<String> requestBodies = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/models", exchange -> respond(exchange, 200, "{\"data\":[]}", "application/json"));
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            if (body.contains("\"tool_call_id\"")) {
                String events = "data: {\"model\":\"provider-model\",\"choices\":[{\"delta\":{\"content\":\"It is sunny.\"}}]}\n\n"
                        + "data: [DONE]\n\n";
                respond(exchange, 200, events, "text/event-stream");
                return;
            }
            String events = "data: {\"model\":\"provider-model\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_abc\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\"}}]}}]}\n\n"
                    + "data: {\"model\":\"provider-model\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Moorpark\\\",\\\"days\\\":2,\\\"metric\\\":true}\"}}]}}]}\n\n"
                    + "data: [DONE]\n\n";
            respond(exchange, 200, events, "text/event-stream");
        });
        server.start();

        ModelProfile profile = new ModelProfile("configured-model",
                Set.of(ModelCapability.TEXT_GENERATION, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING), 10, 20);
        OpenAiCompatibleModelEndpoint endpoint = endpoint(profile);
        Message user = new Message(UUID.randomUUID(), MessageRole.USER, "weather?", Instant.now());
        ToolDefinition weather = new ToolDefinition("get_weather", "Get current weather", List.of(
                new ToolDefinition.Parameter("city", ToolDefinition.Type.STRING, true, "City name"),
                new ToolDefinition.Parameter("days", ToolDefinition.Type.INTEGER, false, "Forecast days"),
                new ToolDefinition.Parameter("metric", ToolDefinition.Type.BOOLEAN, false, "Use metric units")));

        TurnResult first = endpoint.streamTurn(List.of(user), Set.of(ModelCapability.TOOL_CALLING), List.of(weather), ignored -> { });
        assertThat(first).isInstanceOf(ToolRequests.class);
        ToolCall call = ((ToolRequests) first).calls().getFirst();
        UUID normalizedId = UUID.nameUUIDFromBytes("call_abc".getBytes(StandardCharsets.UTF_8));
        assertThat(call.id()).isEqualTo(normalizedId);
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.arguments().get("city")).isEqualTo(new ToolCall.StringValue("Moorpark"));
        assertThat(call.arguments().get("days")).isEqualTo(new ToolCall.IntegerValue(2));
        assertThat(call.arguments().get("metric")).isEqualTo(new ToolCall.BooleanValue(true));

        Message assistantToolRequest = new Message(UUID.randomUUID(), MessageRole.ASSISTANT,
                "Tool requests:\n" + normalizedId + ":get_weather", Instant.now());
        Message toolResult = new Message(UUID.randomUUID(), MessageRole.TOOL,
                "tool_call_id=" + normalizedId + "\ntool=get_weather\nstatus=success\nresult=sunny", Instant.now());
        List<String> deltas = new ArrayList<>();
        TurnResult second = endpoint.streamTurn(List.of(user, assistantToolRequest, toolResult),
                Set.of(ModelCapability.TOOL_CALLING), List.of(weather), deltas::add);

        assertThat(second).isEqualTo(new FinalResponse(new Completion("provider-model", "It is sunny.")));
        assertThat(deltas).containsExactly("It is sunny.");
        assertThat(requestBodies.getFirst())
                .contains("\"tools\"")
                .contains("\"name\":\"get_weather\"")
                .contains("\"required\":[\"city\"]")
                .contains("\"additionalProperties\":false")
                .contains("\"tool_choice\":\"auto\"");
        assertThat(requestBodies.getLast())
                .contains("\"role\":\"assistant\"")
                .contains("\"tool_calls\"")
                .contains("\"id\":\"" + normalizedId + "\"")
                .contains("\"role\":\"tool\"")
                .contains("\"tool_call_id\":\"" + normalizedId + "\"");
    }

    private OpenAiCompatibleModelEndpoint endpoint(ModelProfile profile) {
        return new OpenAiCompatibleModelEndpoint(
                HttpClient.newHttpClient(), new ObjectMapper(),
                URI.create("http://localhost:" + server.getAddress().getPort()),
                "test-key", profile, Duration.ofSeconds(5));
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
