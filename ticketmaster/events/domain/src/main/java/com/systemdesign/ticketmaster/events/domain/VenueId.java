package com.systemdesign.ticketmaster.events.domain;

import java.util.Objects;

public record VenueId(String value) {
    public VenueId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("venue id must not be blank");
        }
    }
}
