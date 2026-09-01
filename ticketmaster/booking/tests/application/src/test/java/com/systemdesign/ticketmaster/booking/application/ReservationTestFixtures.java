package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutService;
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
                hold.expiresAt(),
                hold.checkoutExpiresAt());
    }

    static ReservationCheckoutService service(HoldRepository repository) {
        return new ReservationCheckoutServiceImpl(repository);
    }
}
