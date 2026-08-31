package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Objects;

public record EventAdmission(EventId eventId, Instant admittedThrough) {
    public EventAdmission {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(admittedThrough, "admittedThrough");
    }

    public boolean admits(WaitingRoomEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return eventId.equals(entry.eventId()) && !entry.joinedAt().isAfter(admittedThrough);
    }
}
