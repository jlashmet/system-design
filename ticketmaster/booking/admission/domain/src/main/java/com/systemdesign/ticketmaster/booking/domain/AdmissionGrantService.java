package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Optional short-lived proof that a user already passed waiting-room admission for an event.
 *
 * <p>It is a load-shedding optimization only. Callers must retain the authoritative waiting-room
 * read path when no acceptable grant is available.</p>
 */
public interface AdmissionGrantService {
    Optional<AdmissionGrant> issue(EventId eventId, UserId userId, Instant now);

    boolean accepts(EventId eventId, UserId userId, String token, Instant now);

    static AdmissionGrantService disabled() {
        return new AdmissionGrantService() {
            @Override
            public Optional<AdmissionGrant> issue(EventId eventId, UserId userId, Instant now) {
                return Optional.empty();
            }

            @Override
            public boolean accepts(EventId eventId, UserId userId, String token, Instant now) {
                return false;
            }
        };
    }
}
