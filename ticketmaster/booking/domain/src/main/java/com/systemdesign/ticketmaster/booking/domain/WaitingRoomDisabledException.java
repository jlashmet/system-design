package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public final class WaitingRoomDisabledException extends RuntimeException {
    private final EventId eventId;

    public WaitingRoomDisabledException(EventId eventId) {
        super("waiting room is not enabled for event " + Objects.requireNonNull(eventId, "eventId").value());
        this.eventId = eventId;
    }

    public EventId eventId() {
        return eventId;
    }
}
