package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public record SectionId(String value) {
    public SectionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("section id must not be blank");
    }
}
