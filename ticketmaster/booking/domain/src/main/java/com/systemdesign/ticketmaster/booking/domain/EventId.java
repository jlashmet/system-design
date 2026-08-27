package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public record EventId(String value) {
    public EventId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("event id must not be blank");
    }
}
