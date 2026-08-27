package com.systemdesign.ticketmaster.search.domain;

import java.time.Instant;
import java.util.Objects;

public record SearchEvent(
        String eventId,
        String name,
        String venue,
        String city,
        Instant startsAt,
        String category) {

    public SearchEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(venue, "venue");
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(category, "category");
    }
}
