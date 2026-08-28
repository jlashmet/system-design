package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public final class WaitingRoomEntryNotFoundException extends RuntimeException {
    private final EventId eventId;
    private final UserId userId;

    public WaitingRoomEntryNotFoundException(EventId eventId, UserId userId) {
        super("waiting-room entry not found for user "
                + Objects.requireNonNull(userId, "userId").value()
                + " and event "
                + Objects.requireNonNull(eventId, "eventId").value());
        this.eventId = eventId;
        this.userId = userId;
    }

    public EventId eventId() {
        return eventId;
    }

    public UserId userId() {
        return userId;
    }
}
