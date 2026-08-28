package com.systemdesign.ticketmaster.events.application;

import com.systemdesign.ticketmaster.events.domain.EventId;
import java.util.Objects;

public record BuildEventSearchProjectionQuery(EventId eventId) {
    public BuildEventSearchProjectionQuery {
        Objects.requireNonNull(eventId, "eventId");
    }
}
