package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import java.util.Objects;

public record GetSectionSeatsQuery(EventId eventId, SectionId sectionId) {
    public GetSectionSeatsQuery {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sectionId, "sectionId");
    }
}
