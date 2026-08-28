package com.systemdesign.ticketmaster.events.application;

import java.time.Instant;
import java.util.Objects;

public record EventSearchProjection(
        String eventId,
        String name,
        String venue,
        String city,
        Instant startsAt,
        String category) implements EventSearchProjectionAction {

    public EventSearchProjection {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(venue, "venue");
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(category, "category");
    }
}
