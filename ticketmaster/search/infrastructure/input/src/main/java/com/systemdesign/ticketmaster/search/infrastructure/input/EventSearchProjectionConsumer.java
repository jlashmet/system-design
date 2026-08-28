package com.systemdesign.ticketmaster.search.infrastructure.input;

import com.systemdesign.ticketmaster.search.application.IndexSearchEventHandler;
import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import java.time.Instant;
import java.util.Objects;

public final class EventSearchProjectionConsumer {
    private final IndexSearchEventHandler handler;

    public EventSearchProjectionConsumer(IndexSearchEventHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public void accept(EventSearchProjectionMessage message) {
        Objects.requireNonNull(message, "message");
        handler.handle(new SearchEvent(
                message.eventId(),
                message.name(),
                message.venue(),
                message.city(),
                Instant.ofEpochMilli(message.startsAtEpochMillis()),
                message.category()));
    }
}
