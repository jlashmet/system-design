package com.systemdesign.ticketmaster.booking.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

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

class HmacPaymentWebhookVerifierTest {
    private static final String SECRET = "test-webhook-secret";
    private static final Instant NOW = Instant.parse("2026-08-29T01:15:00Z");
    private static final byte[] ORIGINAL_BODY = "{\"eventId\":\"event-123\",\"bookingId\":\"booking-123\"}"
            .getBytes(StandardCharsets.UTF_8);

    private HmacPaymentWebhookVerifier verifier;
    private String timestamp;
    private String signature;
    private byte[] body;
    private Throwable thrown;

    @Test
    void acceptsValidSignatureInsideReplayWindow() {
        givenValidSignedRequest();
        whenRequestVerified();
        thenExpectAccepted();
    }

    @Test
    void rejectsTamperedBody() {
        givenSignedRequestWithTamperedBody();
        whenRequestVerified();
        thenExpectRejected();
    }

    @Test
    void rejectsStaleTimestamp() {
        givenSignedRequestOlderThanReplayWindow();
        whenRequestVerified();
        thenExpectRejected();
    }

    @Test
    void rejectsFutureTimestampOutsideClockWindow() {
        givenSignedRequestTooFarInFuture();
        whenRequestVerified();
        thenExpectRejected();
    }

    @Test
    void rejectsMalformedSignature() {
        givenRequestWithMalformedSignature();
        whenRequestVerified();
        thenExpectRejected();
    }

    private void givenValidSignedRequest() {
        givenSignedAt(NOW, ORIGINAL_BODY);
    }

    private void givenSignedRequestWithTamperedBody() {
        givenSignedAt(NOW, ORIGINAL_BODY);
        body = "{\"eventId\":\"event-999\",\"bookingId\":\"booking-123\"}"
                .getBytes(StandardCharsets.UTF_8);
    }

    private void givenSignedRequestOlderThanReplayWindow() {
        givenSignedAt(NOW.minus(Duration.ofMinutes(5).plusSeconds(1)), ORIGINAL_BODY);
    }

    private void givenSignedRequestTooFarInFuture() {
        givenSignedAt(NOW.plus(Duration.ofMinutes(5).plusSeconds(1)), ORIGINAL_BODY);
    }

    private void givenRequestWithMalformedSignature() {
        givenSignedAt(NOW, ORIGINAL_BODY);
        signature = "not-hex";
    }

    private void givenSignedAt(Instant signedAt, byte[] signedBody) {
        verifier = new HmacPaymentWebhookVerifier(
                SECRET,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));
        timestamp = Long.toString(signedAt.getEpochSecond());
        body = signedBody.clone();
        signature = sign(timestamp, signedBody);
        thrown = null;
    }

    private void whenRequestVerified() {
        try {
            verifier.verify(timestamp, signature, body);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectAccepted() {
        assertThat(thrown).isNull();
    }

    private void thenExpectRejected() {
        assertThat(thrown).isInstanceOf(PaymentWebhookAuthenticationException.class);
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
}
