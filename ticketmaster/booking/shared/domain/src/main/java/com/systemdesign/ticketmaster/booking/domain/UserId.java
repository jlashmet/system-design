package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public record UserId(String value) {
    public UserId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("user id must not be blank");
    }
}
