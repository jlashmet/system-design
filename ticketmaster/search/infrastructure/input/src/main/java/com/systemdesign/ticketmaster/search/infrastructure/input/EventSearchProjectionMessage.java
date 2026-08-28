package com.systemdesign.ticketmaster.search.infrastructure.input;

import java.util.Objects;

public record EventSearchProjectionMessage(
        String eventId,
        String name,
        String venue,
        String city,
        long startsAtEpochMillis,
        String category) {

    public EventSearchProjectionMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(venue, "venue");
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(category, "category");
    }
}
