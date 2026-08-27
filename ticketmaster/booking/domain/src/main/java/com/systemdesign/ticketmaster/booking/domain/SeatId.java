package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public record SeatId(String value) {
    public SeatId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("seat id must not be blank");
    }
}
