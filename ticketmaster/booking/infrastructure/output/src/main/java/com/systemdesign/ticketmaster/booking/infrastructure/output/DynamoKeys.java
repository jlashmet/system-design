package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class DynamoKeys {
    private DynamoKeys() {
    }

    static String seatPk(EventId eventId, SeatId seatId) {
        return "EVENT#" + eventId.value() + "#SEAT#" + seatId.value();
    }

    static String holdPk(HoldId holdId) {
        return "HOLD#" + holdId.value();
    }

    static String bookingPk(BookingId bookingId) {
        return "BOOKING#" + bookingId.value();
    }

    static String idempotencyPk(EventId eventId, HoldId holdId, String idempotencyKey) {
        String scoped = eventId.value() + "\u0000" + holdId.value() + "\u0000" + idempotencyKey;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(scoped.getBytes(StandardCharsets.UTF_8));
            return "CHECKOUT_IDEMPOTENCY#" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static String reconciliationShard(int shard) {
        return "SHARD#" + shard;
    }

    static int reconciliationShardNumber(String value) {
        return Integer.parseInt(value.substring("SHARD#".length()));
    }
}
