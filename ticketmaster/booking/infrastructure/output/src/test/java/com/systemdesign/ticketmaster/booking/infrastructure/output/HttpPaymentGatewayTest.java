package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import com.systemdesign.ticketmaster.booking.domain.PaymentProviderUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.Price;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Currency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpPaymentGatewayTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final BookingId BOOKING_ID = new BookingId("booking-123");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    private HttpServer server;
    private HttpPaymentGateway gateway;
    private int responseStatus;
    private String responseBody;
    private String requestMethod;
    private String requestPath;
    private String requestIdempotencyKey;
    private String requestBody;
    private PaymentIntent paymentIntent;
    private PaymentIntentStatus paymentStatus;
    private Throwable thrown;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void createsPaymentIntentWithProviderIdempotencyKeyAndRoutingMetadata() {
        givenProviderResponse(201, "{\"id\":\"pi-123\",\"status\":\"REQUIRES_PAYMENT_METHOD\"}");
        whenPaymentIntentCreated();
        thenExpectCreatedIntent("pi-123", PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, "booking-123", "125.00", "USD");
    }

    @Test
    void refusesPaymentCreationWithoutEventRoutingMetadata() {
        givenProviderResponse(201, "{\"id\":\"pi-123\",\"status\":\"REQUIRES_PAYMENT_METHOD\"}");
        whenEventlessPaymentIntentCreated();
        thenExpectEventRoutingRequired();
    }

    @Test
    void readsPaymentStatus() {
        givenProviderResponse(200, "{\"id\":\"pi-123\",\"status\":\"SUCCEEDED\"}");
        whenPaymentStatusRead("pi-123");
        thenExpectStatus(PaymentIntentStatus.SUCCEEDED, "GET", "/payment-intents/pi-123");
    }

    @Test
    void cancelsPaymentIntent() {
        givenProviderResponse(200, "{\"id\":\"pi-123\",\"status\":\"CANCELED\"}");
        whenPaymentIntentCanceled("pi-123");
        thenExpectStatus(PaymentIntentStatus.CANCELED, "POST", "/payment-intents/pi-123/cancel");
    }

    @Test
    void mapsProviderServiceFailureToRetryableUnavailable() {
        givenProviderResponse(503, "{\"error\":\"unavailable\"}");
        whenPaymentIntentCreated();
        thenExpectFailure(PaymentProviderUnavailableException.class, "payment intent creation");
    }

    @Test
    void doesNotMislabelProviderRequestRejectionAsUnavailable() {
        givenProviderResponse(400, "{\"error\":\"bad request\"}");
        whenPaymentIntentCreated();
        thenExpectFailure(IllegalStateException.class, null);
    }

    private void givenProviderResponse(int status, String body) {
        responseStatus = status;
        responseBody = body;
        requestMethod = null;
        requestPath = null;
        requestIdempotencyKey = null;
        requestBody = null;
        paymentIntent = null;
        paymentStatus = null;
        thrown = null;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handleProviderRequest);
        server.start();
        gateway = new HttpPaymentGateway(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1),
                MAPPER);
    }

    private void whenPaymentIntentCreated() {
        try {
            paymentIntent = gateway.createPaymentIntent(EVENT_ID, BOOKING_ID, PRICE, BOOKING_ID.value());
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void whenEventlessPaymentIntentCreated() {
        try {
            paymentIntent = gateway.createPaymentIntent(BOOKING_ID, PRICE, BOOKING_ID.value());
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void whenPaymentStatusRead(String paymentIntentId) {
        try {
            paymentStatus = gateway.getPaymentStatus(paymentIntentId);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void whenPaymentIntentCanceled(String paymentIntentId) {
        try {
            paymentStatus = gateway.cancelPaymentIntent(paymentIntentId);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectCreatedIntent(
            String expectedIntentId,
            PaymentIntentStatus expectedStatus,
            String expectedIdempotencyKey,
            String expectedAmount,
            String expectedCurrency) {
        assertThat(thrown).isNull();
        assertThat(paymentIntent).isEqualTo(new PaymentIntent(expectedIntentId, expectedStatus));
        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestPath).isEqualTo("/payment-intents");
        assertThat(requestIdempotencyKey).isEqualTo(expectedIdempotencyKey);
        try {
            JsonNode json = MAPPER.readTree(requestBody);
            assertThat(json.get("eventId").asText()).isEqualTo(EVENT_ID.value());
            assertThat(json.get("bookingId").asText()).isEqualTo(BOOKING_ID.value());
            assertThat(json.get("amount").asText()).isEqualTo(expectedAmount);
            assertThat(json.get("currency").asText()).isEqualTo(expectedCurrency);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private void thenExpectEventRoutingRequired() {
        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("eventId is required for HTTP payment intent creation");
        assertThat(paymentIntent).isNull();
        assertThat(requestMethod).isNull();
    }

    private void thenExpectStatus(PaymentIntentStatus expectedStatus, String expectedMethod, String expectedPath) {
        assertThat(thrown).isNull();
        assertThat(paymentStatus).isEqualTo(expectedStatus);
        assertThat(requestMethod).isEqualTo(expectedMethod);
        assertThat(requestPath).isEqualTo(expectedPath);
    }

    private void thenExpectFailure(Class<? extends Throwable> expectedType, String expectedOperation) {
        assertThat(thrown).isInstanceOf(expectedType);
        if (expectedOperation != null) {
            assertThat(((PaymentProviderUnavailableException) thrown).operation()).isEqualTo(expectedOperation);
        }
    }

    private void handleProviderRequest(HttpExchange exchange) throws IOException {
        requestMethod = exchange.getRequestMethod();
        requestPath = exchange.getRequestURI().getPath();
        requestIdempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
