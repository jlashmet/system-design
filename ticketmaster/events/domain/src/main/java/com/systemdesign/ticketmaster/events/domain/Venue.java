package com.systemdesign.ticketmaster.events.domain;

import java.util.Objects;

public record Venue(VenueId id, String name, String city) {
    public Venue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(city, "city");
        if (name.isBlank()) throw new IllegalArgumentException("venue name must not be blank");
        if (city.isBlank()) throw new IllegalArgumentException("venue city must not be blank");
    }
}
