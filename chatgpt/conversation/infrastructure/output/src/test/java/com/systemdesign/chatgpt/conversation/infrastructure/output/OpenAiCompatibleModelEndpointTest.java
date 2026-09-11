package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway.Completion;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;
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
        OpenAiCompatibleModelEndpoint endpoint = new OpenAiCompatibleModelEndpoint(
                HttpClient.newHttpClient(), new ObjectMapper(),
                URI.create("http://localhost:" + server.getAddress().getPort()),
                "test-key", profile, Duration.ofSeconds(5));
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

    private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
