package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record StartCheckoutCommand(
        EventId eventId,
        UserId userId,
        List<SeatId> seatIds,
        String idempotencyKey,
        String admissionToken) {

    public StartCheckoutCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
        seatIds = List.copyOf(Objects.requireNonNull(seatIds, "seatIds"));
        if (seatIds.isEmpty()) throw new IllegalArgumentException("at least one seat is required");
        if (seatIds.size() > 96) throw new IllegalArgumentException("checkout cannot contain more than 96 seats");
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new IllegalArgumentException("duplicate seats are not allowed");
        }
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        idempotencyKey = idempotencyKey.trim();
        if (idempotencyKey.isEmpty()) throw new IllegalArgumentException("idempotency key must not be blank");
        if (idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("idempotency key must not exceed 200 characters");
        }
    }
}
