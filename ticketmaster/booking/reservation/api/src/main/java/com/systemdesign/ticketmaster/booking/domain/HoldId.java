package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public record HoldId(String value) {
    public HoldId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("hold id must not be blank");
    }
}
