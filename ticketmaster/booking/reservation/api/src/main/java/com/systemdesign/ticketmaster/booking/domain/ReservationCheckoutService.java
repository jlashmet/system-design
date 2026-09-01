package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Optional;

/** Public Reservation contract used by Checkout process orchestration. */
public interface ReservationCheckoutService {
    ReservationCheckout prepareCheckout(EventId eventId, HoldId holdId, UserId userId,
                                         Instant now, Instant checkoutExpiresAt);

    Optional<ReservationCheckout> findById(HoldId holdId);
}
