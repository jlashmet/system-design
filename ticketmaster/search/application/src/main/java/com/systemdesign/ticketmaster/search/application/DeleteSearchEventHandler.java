package com.systemdesign.ticketmaster.search.application;

import com.systemdesign.ticketmaster.search.domain.EventSearchIndex;
import java.util.Objects;

public final class DeleteSearchEventHandler {
    private final EventSearchIndex index;

    public DeleteSearchEventHandler(EventSearchIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    public void handle(String eventId) {
        Objects.requireNonNull(eventId, "eventId");
        if (eventId.isBlank()) throw new IllegalArgumentException("eventId must not be blank");
        index.delete(eventId);
    }
}
