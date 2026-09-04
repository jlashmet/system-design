package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.PreparedCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutService;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

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

    static ReservationCheckoutService service(HoldRepository repository) {
        return new ReservationCheckoutService() {
            @Override
            public PreparedCheckout prepareCheckout(EventId eventId, UserId userId, Set<SeatId> seatIds,
                                                    String admissionToken, Instant now, Instant checkoutExpiresAt) {
                throw new AssertionError("checkout preparation is not expected in reconciliation fixture");
            }

            @Override
            public Optional<ReservationCheckout> findById(HoldId holdId) {
                return repository.findById(holdId).map(ReservationTestFixtures::from);
            }
        };
    }
}
