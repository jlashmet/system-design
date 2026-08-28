package com.systemdesign.ticketmaster.events.application;

import java.util.Objects;

public record DeleteEventSearchProjection(String eventId) implements EventSearchProjectionAction {
    public DeleteEventSearchProjection {
        Objects.requireNonNull(eventId, "eventId");
        if (eventId.isBlank()) throw new IllegalArgumentException("eventId must not be blank");
    }
}
