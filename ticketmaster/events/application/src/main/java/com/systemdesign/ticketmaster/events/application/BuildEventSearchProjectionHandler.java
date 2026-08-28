package com.systemdesign.ticketmaster.events.application;

import com.systemdesign.ticketmaster.events.domain.Event;
import com.systemdesign.ticketmaster.events.domain.EventRepository;
import com.systemdesign.ticketmaster.events.domain.Venue;
import com.systemdesign.ticketmaster.events.domain.VenueRepository;
import java.util.Objects;
import java.util.Optional;

public final class BuildEventSearchProjectionHandler {
    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;

    public BuildEventSearchProjectionHandler(EventRepository eventRepository, VenueRepository venueRepository) {
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
        this.venueRepository = Objects.requireNonNull(venueRepository, "venueRepository");
    }

    public Optional<EventSearchProjection> handle(BuildEventSearchProjectionQuery query) {
        Objects.requireNonNull(query, "query");
        return eventRepository.findById(query.eventId()).map(this::enrich);
    }

    private EventSearchProjection enrich(Event event) {
        Venue venue = venueRepository.findById(event.venueId())
                .orElseThrow(() -> new IllegalStateException(
                        "event " + event.id().value() + " references missing venue " + event.venueId().value()));
        return new EventSearchProjection(
                event.id().value(),
                event.name(),
                venue.name(),
                venue.city(),
                event.startsAt(),
                event.category());
    }
}
