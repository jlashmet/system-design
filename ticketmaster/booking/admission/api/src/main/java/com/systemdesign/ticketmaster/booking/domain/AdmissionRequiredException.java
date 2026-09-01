package com.systemdesign.ticketmaster.booking.domain;

public final class AdmissionRequiredException extends RuntimeException {
    public AdmissionRequiredException(EventId eventId, UserId userId) {
        super("waiting-room admission is required for user " + userId.value() + " on event " + eventId.value());
    }
}
