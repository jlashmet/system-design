package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.systemdesign.ticketmaster.booking.domain.AdmissionCapacity;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpAdmissionHealthGatewayTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

    private HttpServer server;
    private HttpAdmissionHealthGateway gateway;
    private int responseStatus;
    private String responseBody;
    private String requestPath;
    private String requestQuery;
    private AdmissionCapacity capacity;
    private Throwable thrown;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void acceptsFreshEventCapacitySignal() {
        givenProviderResponse(200, signal("event-123", "HEALTHY", NOW.minusSeconds(1)));
        whenCapacityAssessed();
        thenExpectCapacity(AdmissionCapacity.HEALTHY);
    }

    @Test
    void rejectsStaleHealthySignal() {
        givenProviderResponse(200, signal("event-123", "HEALTHY", NOW.minusSeconds(6)));
        whenCapacityAssessed();
        thenExpectFailure("admission health signal is stale");
    }

    @Test
    void rejectsFutureSignalOutsideClockSkew() {
        givenProviderResponse(200, signal("event-123", "HEALTHY", NOW.plusSeconds(3)));
        whenCapacityAssessed();
        thenExpectFailure("admission health signal is too far in the future");
    }

    @Test
    void rejectsSignalForDifferentEvent() {
        givenProviderResponse(200, signal("event-other", "HEALTHY", NOW));
        whenCapacityAssessed();
        thenExpectFailure("admission health response eventId does not match request");
    }

    @Test
    void rejectsUnknownCapacityInsteadOfAdmitting() {
        givenProviderResponse(200, signal("event-123", "UNKNOWN", NOW));
        whenCapacityAssessed();
        thenExpectFailure("unsupported admission capacity: UNKNOWN");
    }

    @Test
    void rejectsMissingSignalTimestampInsteadOfAdmitting() {
        givenProviderResponse(200, "{\"eventId\":\"event-123\",\"capacity\":\"HEALTHY\"}");
        whenCapacityAssessed();
        thenExpectFailure("admission health observedAt must not be blank");
    }

    @Test
    void rejectsUnavailableTelemetryInsteadOfAdmitting() {
        givenProviderResponse(503, "{\"error\":\"unavailable\"}");
        whenCapacityAssessed();
        thenExpectFailure("admission health source returned HTTP 503");
    }

    private void givenProviderResponse(int status, String body) {
        responseStatus = status;
        responseBody = body;
        requestPath = null;
        requestQuery = null;
        capacity = null;
        thrown = null;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handleRequest);
        server.start();
        gateway = new HttpAdmissionHealthGateway(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(5),
                Duration.ofSeconds(2));
    }

    private void whenCapacityAssessed() {
        try {
            capacity = gateway.assess(EVENT_ID);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectCapacity(AdmissionCapacity expected) {
        assertThat(thrown).isNull();
        assertThat(capacity).isEqualTo(expected);
        assertThat(requestPath).isEqualTo("/admission-capacity");
        assertThat(requestQuery).isEqualTo("eventId=event-123");
    }

    private void thenExpectFailure(String expectedMessage) {
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage(expectedMessage);
        assertThat(capacity).isNull();
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        requestPath = exchange.getRequestURI().getPath();
        requestQuery = exchange.getRequestURI().getRawQuery();
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String signal(String eventId, String value, Instant observedAt) {
        return "{\"eventId\":\"" + eventId + "\",\"capacity\":\"" + value
                + "\",\"observedAt\":\"" + observedAt + "\"}";
    }
}
