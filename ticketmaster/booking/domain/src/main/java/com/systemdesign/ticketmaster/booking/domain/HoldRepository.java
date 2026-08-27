package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Optional;

public interface HoldRepository {
    void createWithSeatClaims(Hold hold, Instant now);
    Optional<Hold> findById(HoldId holdId);
}
