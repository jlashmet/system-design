package com.systemdesign.ticketmaster.events.application;

import com.systemdesign.ticketmaster.events.domain.Event;
import com.systemdesign.ticketmaster.events.domain.EventRepository;
import java.util.Objects;
import java.util.Optional;

public final class GetEventHandler {
    private final EventRepository eventRepository;

    public GetEventHandler(EventRepository eventRepository) {
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
    }

    public Optional<Event> handle(GetEventQuery query) {
        Objects.requireNonNull(query, "query");
        return eventRepository.findById(query.eventId());
    }
}
