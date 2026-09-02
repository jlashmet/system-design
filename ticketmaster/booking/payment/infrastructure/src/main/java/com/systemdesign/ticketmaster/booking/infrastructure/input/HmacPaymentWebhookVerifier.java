package com.systemdesign.ticketmaster.booking.infrastructure.input;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies payment callbacks before any provider-supplied routing metadata is trusted.
 *
 * <p>The signature is lowercase or uppercase hexadecimal HMAC-SHA256 over
 * {@code <epoch-seconds>.<raw-request-body>}. Requests outside the configured clock window are
 * rejected to bound replay exposure.</p>
 */
public final class HmacPaymentWebhookVerifier {
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final byte[] secret;
    private final Clock clock;
    private final Duration maxAge;

    public HmacPaymentWebhookVerifier(String secret, Clock clock, Duration maxAge) {
        String validatedSecret = requireNonBlank(secret, "secret");
        this.secret = validatedSecret.getBytes(StandardCharsets.UTF_8);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxAge = requirePositive(maxAge, "maxAge");
    }

    public void verify(String timestampHeader, String signatureHeader, byte[] body) {
        String timestamp = authenticatedHeader(timestampHeader);
        String signature = authenticatedHeader(signatureHeader);
        Objects.requireNonNull(body, "body");

        Instant signedAt = parseTimestamp(timestamp);
        Duration age = Duration.between(signedAt, clock.instant());
        if (age.compareTo(maxAge) > 0 || age.compareTo(maxAge.negated()) < 0) {
            throw new PaymentWebhookAuthenticationException();
        }

        byte[] suppliedSignature = parseSignature(signature);
        byte[] expectedSignature = sign(timestamp, body);
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
            throw new PaymentWebhookAuthenticationException();
        }
    }

    private byte[] sign(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA_256));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static Instant parseTimestamp(String timestamp) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (RuntimeException e) {
            throw new PaymentWebhookAuthenticationException(e);
        }
    }

    private static byte[] parseSignature(String signature) {
        try {
            byte[] decoded = HexFormat.of().parseHex(signature);
            if (decoded.length != 32) throw new PaymentWebhookAuthenticationException();
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new PaymentWebhookAuthenticationException(e);
        }
    }

    private static String authenticatedHeader(String value) {
        if (value == null || value.isBlank()) throw new PaymentWebhookAuthenticationException();
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
