package com.systemdesign.ticketmaster.booking.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class PaymentProviderWebhookControllerTest {
    private static final String SECRET = "test-webhook-secret";
    private static final Instant NOW = Instant.parse("2026-08-29T01:15:00Z");

    private TrackingHandler handler;
    private PaymentProviderWebhookController controller;
    private String timestamp;
    private String signature;
    private byte[] body;
    private ResponseEntity<Void> response;
    private Throwable thrown;

    @Test
    void verifiedWebhookInvokesTrustedConsumer() {
        givenSignedBody("{\"eventId\":\"event-123\",\"bookingId\":\"booking-123\"}");
        whenWebhookReceived();
        thenExpectAccepted("event-123", "booking-123");
    }

    @Test
    void invalidSignatureNeverReachesTrustedConsumer() {
        givenRequestWithInvalidSignature();
        whenWebhookReceived();
        thenExpectRejected(PaymentWebhookAuthenticationException.class);
    }

    @Test
    void missingSignatureNeverReachesTrustedConsumer() {
        givenRequestWithMissingSignature();
        whenWebhookReceived();
        thenExpectRejected(PaymentWebhookAuthenticationException.class);
    }

    @Test
    void malformedJsonAfterValidAuthenticationIsBadRequest() {
        givenSignedBody("{\"eventId\":");
        whenWebhookReceived();
        thenExpectRejected(IllegalArgumentException.class);
    }

    @Test
    void providerStatusFieldIsRejectedRatherThanTrusted() {
        givenSignedBody("{\"eventId\":\"event-123\",\"bookingId\":\"booking-123\",\"status\":\"SUCCEEDED\"}");
        whenWebhookReceived();
        thenExpectRejected(IllegalArgumentException.class);
    }

    @Test
    void blankRoutingMetadataIsBadRequest() {
        givenSignedBody("{\"eventId\":\"\",\"bookingId\":\"booking-123\"}");
        whenWebhookReceived();
        thenExpectRejected(IllegalArgumentException.class);
    }

    private void givenRequestWithInvalidSignature() {
        givenSignedBody("{\"eventId\":\"event-123\",\"bookingId\":\"booking-123\"}");
        signature = "00".repeat(32);
    }

    private void givenRequestWithMissingSignature() {
        givenSignedBody("{\"eventId\":\"event-123\",\"bookingId\":\"booking-123\"}");
        signature = null;
    }

    private void givenSignedBody(String json) {
        handler = new TrackingHandler();
        HmacPaymentWebhookVerifier verifier = new HmacPaymentWebhookVerifier(
                SECRET,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));
        controller = new PaymentProviderWebhookController(verifier, handler);
        timestamp = Long.toString(NOW.getEpochSecond());
        body = json.getBytes(StandardCharsets.UTF_8);
        signature = sign(timestamp, body);
        response = null;
        thrown = null;
    }

    private void whenWebhookReceived() {
        try {
            response = controller.paymentStatusChanged(timestamp, signature, body);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectAccepted(String expectedEventId, String expectedBookingId) {
        assertThat(thrown).isNull();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(handler.calls).isOne();
        assertThat(handler.eventId).isEqualTo(expectedEventId);
        assertThat(handler.bookingId).isEqualTo(expectedBookingId);
    }

    private void thenExpectRejected(Class<? extends Throwable> expectedType) {
        assertThat(thrown).isInstanceOf(expectedType);
        assertThat(response).isNull();
        assertThat(handler.calls).isZero();
    }

    private static String sign(String timestamp, byte[] signedBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return HexFormat.of().formatHex(mac.doFinal(signedBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class TrackingHandler implements VerifiedPaymentStatusChangedHandler {
        private int calls;
        private String eventId;
        private String bookingId;

        @Override
        public Booking accept(String eventId, String bookingId) {
            calls++;
            this.eventId = eventId;
            this.bookingId = bookingId;
            return null;
        }
    }
}
