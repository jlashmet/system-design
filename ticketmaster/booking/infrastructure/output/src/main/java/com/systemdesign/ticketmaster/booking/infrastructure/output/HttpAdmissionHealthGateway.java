package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.ticketmaster.booking.domain.AdmissionCapacity;
import com.systemdesign.ticketmaster.booking.domain.AdmissionHealthGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;

/**
 * Reads event-specific admission capacity from an internal telemetry/capacity service.
 *
 * <p>Only fresh signals are accepted. A stale, future-skewed, malformed, mismatched, or
 * unavailable response fails the assessment so the admission scheduler holds the watermark
 * steady rather than admitting users on uncertain capacity.</p>
 */
public final class HttpAdmissionHealthGateway implements AdmissionHealthGateway {
    private static final String JSON = "application/json";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration maxSignalAge;
    private final Duration maxFutureSkew;

    public HttpAdmissionHealthGateway(
            HttpClient httpClient,
            URI baseUri,
            Duration requestTimeout,
            ObjectMapper objectMapper,
            Clock clock,
            Duration maxSignalAge,
            Duration maxFutureSkew) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        URI validatedBaseUri = requireHttpBaseUri(baseUri);
        String rawBaseUrl = validatedBaseUri.toString();
        this.baseUrl = rawBaseUrl.endsWith("/")
                ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1)
                : rawBaseUrl;
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxSignalAge = requirePositive(maxSignalAge, "maxSignalAge");
        this.maxFutureSkew = requireNonNegative(maxFutureSkew, "maxFutureSkew");
    }

    @Override
    public AdmissionCapacity assess(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        HttpRequest request = HttpRequest.newBuilder(uri(eventId))
                .timeout(requestTimeout)
                .header("Accept", JSON)
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        CapacitySignal signal = readSignal(response.body());
        if (!eventId.value().equals(signal.eventId())) {
            throw new IllegalStateException("admission health response eventId does not match request");
        }
        validateFreshness(signal.observedAt());
        return parseCapacity(signal.capacity());
    }

    private HttpResponse<String> send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("admission health source unavailable", e);
        } catch (IOException e) {
            throw new IllegalStateException("admission health source unavailable", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("admission health source returned HTTP " + response.statusCode());
        }
        return response;
    }

    private CapacitySignal readSignal(String body) {
        try {
            CapacitySignal signal = objectMapper.readValue(body, CapacitySignal.class);
            if (signal == null) throw new IllegalStateException("admission health response must be an object");
            return new CapacitySignal(
                    requireNonBlank(signal.eventId(), "admission health eventId"),
                    requireNonBlank(signal.capacity(), "admission health capacity"),
                    requireNonBlank(signal.observedAt(), "admission health observedAt"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid admission health response", e);
        }
    }

    private void validateFreshness(String observedAtValue) {
        Instant observedAt;
        try {
            observedAt = Instant.parse(observedAtValue);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("invalid admission health observedAt", e);
        }
        Instant now = clock.instant();
        if (observedAt.isBefore(now.minus(maxSignalAge))) {
            throw new IllegalStateException("admission health signal is stale");
        }
        if (observedAt.isAfter(now.plus(maxFutureSkew))) {
            throw new IllegalStateException("admission health signal is too far in the future");
        }
    }

    private static AdmissionCapacity parseCapacity(String value) {
        try {
            return AdmissionCapacity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("unsupported admission capacity: " + value, e);
        }
    }

    private URI uri(EventId eventId) {
        String encodedEventId = URLEncoder.encode(eventId.value(), StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(baseUrl + "/admission-capacity?eventId=" + encodedEventId);
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

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must not be blank");
        return value;
    }

    record CapacitySignal(String eventId, String capacity, String observedAt) {}
}
