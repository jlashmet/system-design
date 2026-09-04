package com.systemdesign.ticketmaster.booking.domain;

import java.util.Optional;
import java.util.Set;

public interface HoldRepository {
    SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds);
    Optional<Hold> findById(HoldId holdId);
}
