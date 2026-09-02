package com.systemdesign.ticketmaster.events.application;

import com.systemdesign.ticketmaster.events.domain.EventId;
import com.systemdesign.ticketmaster.events.domain.VenueId;
import java.time.Instant;
import java.util.Objects;

public record CreateEventCommand(
        EventId eventId,
        String name,
        VenueId venueId,
        Instant startsAt,
        String category,
        String description) {

    public CreateEventCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(category, "category");
        description = description == null ? "" : description;
    }
}
