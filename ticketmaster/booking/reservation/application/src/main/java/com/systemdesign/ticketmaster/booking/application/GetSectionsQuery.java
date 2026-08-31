package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.util.Objects;

public record GetSectionsQuery(EventId eventId) {
    public GetSectionsQuery {
        Objects.requireNonNull(eventId, "eventId");
    }
}
