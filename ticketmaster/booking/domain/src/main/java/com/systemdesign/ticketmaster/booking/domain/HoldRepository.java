package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface HoldRepository {
    SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds);
    void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now, HoldIdempotencyKey idempotencyKey);
    Optional<Hold> findById(HoldId holdId);

    /**
     * Resolves a hold idempotency key within the customer/event scope where it was submitted.
     * Production adapters should override this method so unrelated users/events may safely reuse
     * the same client-generated key value.
     */
    default Optional<Hold> findByIdempotencyKey(
            EventId eventId, UserId userId, HoldIdempotencyKey idempotencyKey) {
        return findByIdempotencyKey(idempotencyKey);
    }

    /**
     * Compatibility fallback for simple in-memory test fakes. Production code should use the
     * scoped overload above; production persistence adapters may reject this unscoped lookup.
     */
    @Deprecated
    Optional<Hold> findByIdempotencyKey(HoldIdempotencyKey idempotencyKey);
}
