package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;
import com.systemdesign.chatgpt.conversation.domain.ModelProviderException;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleModelEndpointFailureTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void classifiesClientAndAuthErrorsAsPermanent() throws Exception {
        assertFailure(401, false, null, null);
    }

    @Test
    void classifiesCapacityAndServerErrorsAsRetryable() throws Exception {
        assertFailure(429, true, null, null);
        stopServer();
        server = null;
        assertFailure(503, true, null, null);
    }

    @Test
    void capturesDeltaSecondsRetryAfterOnRetryableFailure() throws Exception {
        assertFailure(429, true, "7", Duration.ofSeconds(7));
    }

    @Test
    void ignoresMalformedRetryAfterHeader() throws Exception {
        assertFailure(503, true, "not-a-delay", null);
    }

    private void assertFailure(int status, boolean retryable, String retryAfterHeader, Duration expectedRetryAfter)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            if (retryAfterHeader != null) exchange.getResponseHeaders().add("Retry-After", retryAfterHeader);
            respond(exchange, status, "provider failure");
        });
        server.start();
        OpenAiCompatibleModelEndpoint endpoint = new OpenAiCompatibleModelEndpoint(
                HttpClient.newHttpClient(), new ObjectMapper(),
                URI.create("http://localhost:" + server.getAddress().getPort()), "key",
                new ModelProfile("test", Set.of(ModelCapability.TEXT_GENERATION), 0, 0), Duration.ofSeconds(5));
        Message user = new Message(UUID.randomUUID(), MessageRole.USER, "hello", Instant.now());

        assertThatThrownBy(() -> endpoint.complete(List.of(user)))
                .isInstanceOf(ModelProviderException.class)
                .satisfies(error -> {
                    ModelProviderException provider = (ModelProviderException) error;
                    org.assertj.core.api.Assertions.assertThat(provider.retryable()).isEqualTo(retryable);
                    org.assertj.core.api.Assertions.assertThat(provider.statusCode()).isEqualTo(status);
                    org.assertj.core.api.Assertions.assertThat(provider.retryAfter()).isEqualTo(expectedRetryAfter);
                });
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
