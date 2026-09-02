package com.systemdesign.ticketmaster.events.domain;

import java.util.Optional;

public interface EventRepository {
    Optional<Event> findById(EventId eventId);
}
