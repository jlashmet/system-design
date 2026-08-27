package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Objects;

public record WaitingRoomEntry(EventId eventId, UserId userId, Instant joinedAt) {
    public WaitingRoomEntry {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }
}
