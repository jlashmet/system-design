package com.systemdesign.ticketmaster.booking.infrastructure.input;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/payment-provider")
@ConditionalOnProperty(name = "ticketmaster.booking.payment.webhook.enabled", havingValue = "true")
public final class PaymentProviderWebhookController {
    public static final String TIMESTAMP_HEADER = "X-Payment-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Payment-Signature";

    private final HmacPaymentWebhookVerifier verifier;
    private final VerifiedPaymentStatusChangedHandler handler;
    private final ObjectMapper objectMapper;

    public PaymentProviderWebhookController(
            HmacPaymentWebhookVerifier verifier,
            VerifiedPaymentStatusChangedHandler handler,
            ObjectMapper objectMapper) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    @PostMapping(path = "/events", consumes = "application/json")
    public ResponseEntity<Void> paymentStatusChanged(
            @RequestHeader(TIMESTAMP_HEADER) String timestamp,
            @RequestHeader(SIGNATURE_HEADER) String signature,
            @RequestBody byte[] rawBody) {
        verifier.verify(timestamp, signature, rawBody);
        PaymentStatusChangedRequest request = readRequest(rawBody);
        handler.accept(
                requireNonBlank(request.eventId(), "eventId"),
                requireNonBlank(request.bookingId(), "bookingId"));
        return ResponseEntity.noContent().build();
    }

    private PaymentStatusChangedRequest readRequest(byte[] rawBody) {
        try {
            PaymentStatusChangedRequest request = objectMapper.readValue(rawBody, PaymentStatusChangedRequest.class);
            if (request == null) throw new IllegalArgumentException("payment webhook body must be an object");
            return request;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid payment webhook body", e);
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private record PaymentStatusChangedRequest(String eventId, String bookingId) {}
}
