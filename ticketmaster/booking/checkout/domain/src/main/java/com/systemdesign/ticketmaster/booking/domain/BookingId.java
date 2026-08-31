package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public record BookingId(String value) {
    public BookingId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("booking id must not be blank");
    }
}
