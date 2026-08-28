package com.systemdesign.ticketmaster.controlplane.application;

import com.systemdesign.ticketmaster.controlplane.domain.EventId;
import java.util.Objects;

public record GetEventOwnershipQuery(EventId eventId) {
    public GetEventOwnershipQuery {
        Objects.requireNonNull(eventId, "eventId");
    }
}
