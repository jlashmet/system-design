package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventOwnershipUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpEventWriteAuthorityTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void permitsWritesWhenLocalRegionOwnsEvent() throws Exception {
        givenResponse(200, "{\"eventId\":\"event-123\",\"ownerRegion\":\"us-west-2\",\"epoch\":7}");
        HttpEventWriteAuthority authority = authority("us-west-2");

        assertThatCode(() -> authority.assertMayWrite(EVENT_ID)).doesNotThrowAnyException();
    }

    @Test
    void rejectsWritesWhenAnotherRegionOwnsEvent() throws Exception {
        givenResponse(200, "{\"eventId\":\"event-123\",\"ownerRegion\":\"us-east-1\",\"epoch\":8}");
        HttpEventWriteAuthority authority = authority("us-west-2");

        assertThatThrownBy(() -> authority.assertMayWrite(EVENT_ID))
                .isInstanceOf(WrongBookingRegionException.class)
                .hasMessageContaining("us-east-1")
                .hasMessageContaining("us-west-2");
    }

    @Test
    void failsClosedWhenOwnershipIsMissing() throws Exception {
        givenResponse(404, "");
        HttpEventWriteAuthority authority = authority("us-west-2");

        assertThatThrownBy(() -> authority.assertMayWrite(EVENT_ID))
                .isInstanceOf(EventOwnershipUnavailableException.class)
                .hasMessageContaining("has not been assigned");
    }

    @Test
    void failsClosedWhenOwnershipResponseIsMalformed() throws Exception {
        givenResponse(200, "{\"eventId\":\"event-123\",\"ownerRegion\":\"us-west-2\",\"epoch\":0}");
        HttpEventWriteAuthority authority = authority("us-west-2");

        assertThatThrownBy(() -> authority.assertMayWrite(EVENT_ID))
                .isInstanceOf(EventOwnershipUnavailableException.class)
                .hasMessageContaining("inconsistent ownership");
    }

    @Test
    void failsClosedWhenControlPlaneCannotBeReached() {
        HttpEventWriteAuthority authority = new HttpEventWriteAuthority(
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:1"),
                "us-west-2",
                Duration.ofMillis(200));

        assertThatThrownBy(() -> authority.assertMayWrite(EVENT_ID))
                .isInstanceOf(EventOwnershipUnavailableException.class)
                .hasMessageContaining("control plane is unavailable");
    }

    private HttpEventWriteAuthority authority(String localRegion) {
        return new HttpEventWriteAuthority(
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                localRegion,
                Duration.ofSeconds(1));
    }

    private void givenResponse(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/control-plane/events/event-123/ownership", exchange -> respond(exchange, status, body));
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
