package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** Public Reservation contract used by Checkout process orchestration. */
public interface ReservationCheckoutService {
    PreparedCheckout prepareCheckout(EventId eventId, UserId userId, Set<SeatId> seatIds,
                                     String admissionToken, Instant now, Instant checkoutExpiresAt);

    Optional<ReservationCheckout> findById(HoldId holdId);
}
