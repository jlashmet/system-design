package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface HoldRepository {
    SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds);
    void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now);
    Optional<Hold> findById(HoldId holdId);
}
