package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.AdmissionGrant;
import com.systemdesign.ticketmaster.booking.domain.AdmissionGrantService;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Compact HMAC-SHA256 admission grants scoped to one event/user pair.
 *
 * <p>Token format: {@code v1.<event-b64url>.<user-b64url>.<expiry-epoch-millis>.<signature-b64url>}.
 * The signature covers every segment before the signature itself.</p>
 */
public final class HmacAdmissionGrantService implements AdmissionGrantService {
    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final String VERSION = "v1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Duration ttl;

    public HmacAdmissionGrantService(String secret, Duration ttl) {
        String validatedSecret = requireNonBlank(secret, "secret");
        this.secret = validatedSecret.getBytes(StandardCharsets.UTF_8);
        this.ttl = requirePositive(ttl, "ttl");
    }

    @Override
    public Optional<AdmissionGrant> issue(EventId eventId, UserId userId, Instant now) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(now, "now");
        Instant expiresAt = now.plus(ttl);
        String unsigned = VERSION
                + "." + encode(eventId.value())
                + "." + encode(userId.value())
                + "." + expiresAt.toEpochMilli();
        String token = unsigned + "." + ENCODER.encodeToString(sign(unsigned));
        return Optional.of(new AdmissionGrant(token, expiresAt));
    }

    @Override
    public boolean accepts(EventId eventId, UserId userId, String token, Instant now) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(now, "now");
        if (token == null || token.isBlank()) return false;

        String[] parts = token.split("\\.", -1);
        if (parts.length != 5 || !VERSION.equals(parts[0])) return false;

        String tokenEvent;
        String tokenUser;
        long expiresEpochMilli;
        byte[] suppliedSignature;
        try {
            tokenEvent = decode(parts[1]);
            tokenUser = decode(parts[2]);
            expiresEpochMilli = Long.parseLong(parts[3]);
            suppliedSignature = DECODER.decode(parts[4]);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (suppliedSignature.length != 32) return false;
        if (!eventId.value().equals(tokenEvent) || !userId.value().equals(tokenUser)) return false;

        Instant expiresAt;
        try {
            expiresAt = Instant.ofEpochMilli(expiresEpochMilli);
        } catch (RuntimeException malformedExpiry) {
            return false;
        }
        if (!now.isBefore(expiresAt)) return false;

        String unsigned = String.join(".", parts[0], parts[1], parts[2], parts[3]);
        return MessageDigest.isEqual(sign(unsigned), suppliedSignature);
    }

    private byte[] sign(String unsigned) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA_256));
            return mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
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
