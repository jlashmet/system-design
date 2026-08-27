package com.systemdesign.ticketmaster.events.domain;

import java.time.Instant;
import java.util.Objects;

public record Event(
        EventId id,
        String name,
        VenueId venueId,
        Instant startsAt,
        String category,
        EventStatus status,
        String description) {

    public Event {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(status, "status");
        if (name.isBlank()) {
            throw new IllegalArgumentException("event name must not be blank");
        }
        if (category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        description = description == null ? "" : description;
    }
}
