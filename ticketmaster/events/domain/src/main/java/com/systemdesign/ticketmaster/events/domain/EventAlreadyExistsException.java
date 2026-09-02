package com.systemdesign.ticketmaster.events.domain;

import java.util.Objects;

public final class EventAlreadyExistsException extends RuntimeException {
    private final EventId eventId;

    public EventAlreadyExistsException(EventId eventId) {
        super("event already exists: " + Objects.requireNonNull(eventId, "eventId").value());
        this.eventId = eventId;
    }

    public EventId eventId() {
        return eventId;
    }
}
