package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import com.systemdesign.ticketmaster.booking.domain.PaymentProviderUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.Price;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * HTTP adapter for a payment-intent provider or provider-facing payment service.
 *
 * <p>The provider contract is deliberately small and mirrors the domain port:
 * POST /payment-intents, GET /payment-intents/{id}, and POST /payment-intents/{id}/cancel.
 * Creation carries the Booking ID as the provider idempotency key and the Event ID as callback
 * routing metadata. A lost response can therefore be retried without creating a second payment
 * intent, and a later verified callback can be routed before regional Booking storage is touched.</p>
 */
public final class HttpPaymentGateway implements PaymentGateway {
    private static final String JSON = "application/json";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;

    public HttpPaymentGateway(
            HttpClient httpClient,
            URI baseUri,
            Duration requestTimeout,
            ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        URI validatedBaseUri = requireHttpBaseUri(baseUri);
        String rawBaseUrl = validatedBaseUri.toString();
        this.baseUrl = rawBaseUrl.endsWith("/")
                ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1)
                : rawBaseUrl;
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey) {
        throw new IllegalStateException("eventId is required for HTTP payment intent creation");
    }

    @Override
    public PaymentIntent createPaymentIntent(
            EventId eventId, BookingId bookingId, Price price, String idempotencyKey) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(price, "price");
        String key = requireNonBlank(idempotencyKey, "idempotencyKey");
        String body = writeJson(new CreatePaymentIntentRequest(
                eventId.value(), bookingId.value(), price.amount(), price.currency().getCurrencyCode()));
        HttpRequest request = HttpRequest.newBuilder(uri("/payment-intents"))
                .timeout(requestTimeout)
                .header("Accept", JSON)
                .header("Content-Type", JSON)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return readIntent(send(request, "payment intent creation"), "payment intent creation", null);
    }

    @Override
    public PaymentIntentStatus getPaymentStatus(String paymentIntentId) {
        String intentId = requireNonBlank(paymentIntentId, "paymentIntentId");
        HttpRequest request = HttpRequest.newBuilder(uri("/payment-intents/" + pathSegment(intentId)))
                .timeout(requestTimeout)
                .header("Accept", JSON)
                .GET()
                .build();
        return readIntent(send(request, "payment status lookup"), "payment status lookup", intentId).status();
    }

    @Override
    public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) {
        String intentId = requireNonBlank(paymentIntentId, "paymentIntentId");
        HttpRequest request = HttpRequest.newBuilder(uri("/payment-intents/" + pathSegment(intentId) + "/cancel"))
                .timeout(requestTimeout)
                .header("Accept", JSON)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return readIntent(send(request, "payment cancellation"), "payment cancellation", intentId).status();
    }

    private HttpResponse<String> send(HttpRequest request, String operation) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentProviderUnavailableException(operation, e);
        } catch (IOException e) {
            throw new PaymentProviderUnavailableException(operation, e);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) return response;
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            throw new PaymentProviderUnavailableException(
                    operation,
                    new IllegalStateException("payment provider returned HTTP " + status));
        }
        throw new IllegalStateException("payment provider rejected " + operation + " with HTTP " + status);
    }

    private PaymentIntent readIntent(HttpResponse<String> response, String operation, String expectedIntentId) {
        ProviderPaymentIntent providerIntent;
        try {
            providerIntent = objectMapper.readValue(response.body(), ProviderPaymentIntent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid payment provider response during " + operation, e);
        }

        String id = requireNonBlank(providerIntent.id(), "provider payment intent id");
        if (expectedIntentId != null && !expectedIntentId.equals(id)) {
            throw new IllegalStateException("payment provider returned a different payment intent during " + operation);
        }
        PaymentIntentStatus status;
        try {
            status = PaymentIntentStatus.valueOf(
                    requireNonBlank(providerIntent.status(), "provider payment intent status")
                            .trim()
                            .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("unsupported payment provider status during " + operation, e);
        }
        return new PaymentIntent(id, status);
    }

    private String writeJson(CreatePaymentIntentRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize payment intent request", e);
        }
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static URI requireHttpBaseUri(URI value) {
        Objects.requireNonNull(value, "baseUri");
        String scheme = value.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || value.getHost() == null) {
            throw new IllegalArgumentException("baseUri must be an absolute HTTP(S) URI");
        }
        if (value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("baseUri must not contain query or fragment");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    record CreatePaymentIntentRequest(String eventId, String bookingId, java.math.BigDecimal amount, String currency) {}

    record ProviderPaymentIntent(String id, String status) {}
}
