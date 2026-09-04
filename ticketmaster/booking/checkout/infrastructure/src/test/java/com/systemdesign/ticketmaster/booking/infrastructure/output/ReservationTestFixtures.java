package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;

final class ReservationTestFixtures {
    private ReservationTestFixtures() {
    }

    static ReservationCheckout from(Hold hold) {
        return new ReservationCheckout(
                hold.id(),
                hold.userId(),
                hold.eventId(),
                hold.seatIds(),
                hold.totalPrice(),
                ReservationCheckoutStatus.valueOf(hold.status().name()),
                hold.checkoutExpiresAt());
    }
}
