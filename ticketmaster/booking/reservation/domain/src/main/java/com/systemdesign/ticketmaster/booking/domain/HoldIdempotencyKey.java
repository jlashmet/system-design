package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public record HoldIdempotencyKey(String value) {
    public HoldIdempotencyKey {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("idempotency key must not be blank");
        if (value.length() > 200) throw new IllegalArgumentException("idempotency key must not exceed 200 characters");
    }
}
