package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventOwnershipUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class HttpEventWriteAuthority implements EventWriteAuthority {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI controlPlaneBaseUri;
    private final String localRegion;
    private final Duration requestTimeout;

    public HttpEventWriteAuthority(HttpClient httpClient, URI controlPlaneBaseUri,
                                   String localRegion, Duration requestTimeout) {
        this(httpClient, new ObjectMapper(), controlPlaneBaseUri, localRegion, requestTimeout);
    }

    HttpEventWriteAuthority(HttpClient httpClient, ObjectMapper objectMapper, URI controlPlaneBaseUri,
                            String localRegion, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.controlPlaneBaseUri = Objects.requireNonNull(controlPlaneBaseUri, "controlPlaneBaseUri");
        this.localRegion = requireText(localRegion, "localRegion");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    @Override
    public void assertMayWrite(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        HttpRequest request = HttpRequest.newBuilder(ownershipUri(eventId))
                .timeout(requestTimeout)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EventOwnershipUnavailableException(eventId, "ownership lookup was interrupted", interrupted);
        } catch (IOException | RuntimeException failure) {
            throw new EventOwnershipUnavailableException(eventId, "control plane is unavailable", failure);
        }

        if (response.statusCode() == 404) {
            throw new EventOwnershipUnavailableException(eventId, "ownership has not been assigned");
        }
        if (response.statusCode() != 200) {
            throw new EventOwnershipUnavailableException(eventId,
                    "control plane returned HTTP " + response.statusCode());
        }

        OwnershipResponse ownership;
        try {
            ownership = objectMapper.readValue(response.body(), OwnershipResponse.class);
        } catch (IOException | RuntimeException malformed) {
            throw new EventOwnershipUnavailableException(eventId, "control plane returned malformed ownership", malformed);
        }

        if (!eventId.value().equals(ownership.eventId()) || ownership.epoch() < 1
                || ownership.ownerRegion() == null || ownership.ownerRegion().isBlank()) {
            throw new EventOwnershipUnavailableException(eventId, "control plane returned inconsistent ownership");
        }
        if (!localRegion.equals(ownership.ownerRegion())) {
            throw new WrongBookingRegionException(eventId, localRegion, ownership.ownerRegion());
        }
    }

    private URI ownershipUri(EventId eventId) {
        String encodedEventId = URLEncoder.encode(eventId.value(), StandardCharsets.UTF_8).replace("+", "%20");
        String base = controlPlaneBaseUri.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return URI.create(base + "/control-plane/events/" + encodedEventId + "/ownership");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private record OwnershipResponse(String eventId, String ownerRegion, long epoch) {}
}
