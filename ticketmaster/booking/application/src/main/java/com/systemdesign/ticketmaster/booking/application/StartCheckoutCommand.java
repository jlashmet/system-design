package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.HoldId;
import java.util.Objects;

public record StartCheckoutCommand(HoldId holdId, String idempotencyKey) {
    public StartCheckoutCommand {
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotency key must not be blank");
    }
}
