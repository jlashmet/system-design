package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class DynamoReservationKeys {
    private DynamoReservationKeys() {
    }

    static String eventOwnershipPk(EventId eventId) {
        return "EVENT#" + eventId.value() + "#OWNERSHIP";
    }

    static String seatPk(EventId eventId, SeatId seatId) {
        return "EVENT#" + eventId.value() + "#SEAT#" + seatId.value();
    }

    static String holdPk(HoldId holdId) {
        return "HOLD#" + holdId.value();
    }

    static String holdIdempotencyPk(EventId eventId, UserId userId, HoldIdempotencyKey idempotencyKey) {
        String scoped = eventId.value() + "\u0000" + userId.value() + "\u0000" + idempotencyKey.value();
        return "HOLD_IDEMPOTENCY#" + sha256(scoped);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
