package com.systemdesign.ticketmaster.search.infrastructure.input;

import com.systemdesign.ticketmaster.search.application.DeleteSearchEventHandler;
import com.systemdesign.ticketmaster.search.application.IndexSearchEventHandler;
import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import java.time.Instant;
import java.util.Objects;

public final class EventSearchProjectionConsumer {
    private final IndexSearchEventHandler indexHandler;
    private final DeleteSearchEventHandler deleteHandler;

    public EventSearchProjectionConsumer(
            IndexSearchEventHandler indexHandler,
            DeleteSearchEventHandler deleteHandler) {
        this.indexHandler = Objects.requireNonNull(indexHandler, "indexHandler");
        this.deleteHandler = Objects.requireNonNull(deleteHandler, "deleteHandler");
    }

    public void accept(EventSearchProjectionMessage message) {
        Objects.requireNonNull(message, "message");
        indexHandler.handle(new SearchEvent(
                message.eventId(),
                message.name(),
                message.venue(),
                message.city(),
                Instant.ofEpochMilli(message.startsAtEpochMillis()),
                message.category()));
    }

    public void accept(EventSearchDeletionMessage message) {
        Objects.requireNonNull(message, "message");
        deleteHandler.handle(message.eventId());
    }
}
