package com.systemdesign.ticketmaster.events.domain;

import java.util.Optional;

public interface EventRepository {
    void create(Event event);

    Optional<Event> findById(EventId eventId);
}
