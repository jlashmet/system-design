package com.systemdesign.ticketmaster.search.infrastructure.input;

import java.util.Objects;

public record EventSearchDeletionMessage(String eventId) {
    public EventSearchDeletionMessage {
        Objects.requireNonNull(eventId, "eventId");
        if (eventId.isBlank()) throw new IllegalArgumentException("eventId must not be blank");
    }
}
