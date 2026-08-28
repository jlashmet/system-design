package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.util.List;
import java.util.Objects;

public record CreateHoldCommand(
        UserId userId,
        EventId eventId,
        List<SeatId> seatIds,
        HoldIdempotencyKey idempotencyKey) {
    public CreateHoldCommand {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(eventId, "eventId");
        seatIds = List.copyOf(seatIds);
        if (seatIds.isEmpty()) throw new IllegalArgumentException("at least one seat is required");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
