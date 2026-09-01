package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;

/** Public admission contract consumed by Reservation. */
public interface AdmissionAccess {
    void requireAdmission(EventId eventId, UserId userId, String admissionToken, Instant now);

    static AdmissionAccess disabled() {
        return (eventId, userId, admissionToken, now) -> { };
    }
}
