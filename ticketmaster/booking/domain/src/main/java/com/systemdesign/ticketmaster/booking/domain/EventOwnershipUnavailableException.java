package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public final class EventOwnershipUnavailableException extends RuntimeException {
    private final EventId eventId;

    public EventOwnershipUnavailableException(EventId eventId, String detail) {
        super("cannot establish booking ownership for event "
                + Objects.requireNonNull(eventId, "eventId").value() + ": " + requireText(detail));
        this.eventId = eventId;
    }

    public EventOwnershipUnavailableException(EventId eventId, String detail, Throwable cause) {
        super("cannot establish booking ownership for event "
                + Objects.requireNonNull(eventId, "eventId").value() + ": " + requireText(detail), cause);
        this.eventId = eventId;
    }

    public EventId eventId() { return eventId; }

    private static String requireText(String value) {
        Objects.requireNonNull(value, "detail");
        if (value.isBlank()) throw new IllegalArgumentException("detail must not be blank");
        return value;
    }
}
