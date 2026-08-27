package com.systemdesign.ticketmaster.events.application;

import com.systemdesign.ticketmaster.events.domain.EventId;
import java.util.Objects;

public record GetEventQuery(EventId eventId) {
    public GetEventQuery {
        Objects.requireNonNull(eventId, "eventId");
    }
}
