package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;
import java.util.Set;

public final class SeatClaimConflictException extends RuntimeException {
    private final EventId eventId;
    private final Set<SeatId> seatIds;

    public SeatClaimConflictException(EventId eventId, Set<SeatId> seatIds) {
        super("one or more seats are unavailable for event " + Objects.requireNonNull(eventId, "eventId").value());
        this.eventId = eventId;
        this.seatIds = Set.copyOf(seatIds);
    }

    public EventId eventId() {
        return eventId;
    }

    public Set<SeatId> seatIds() {
        return seatIds;
    }
}
