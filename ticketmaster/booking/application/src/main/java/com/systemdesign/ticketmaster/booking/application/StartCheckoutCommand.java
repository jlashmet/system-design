package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.util.Objects;

public record StartCheckoutCommand(EventId eventId, HoldId holdId, UserId userId, String idempotencyKey) {
    public StartCheckoutCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        idempotencyKey = idempotencyKey.trim();
        if (idempotencyKey.isEmpty()) throw new IllegalArgumentException("idempotency key must not be blank");
        if (idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("idempotency key must not exceed 200 characters");
        }
    }
}
