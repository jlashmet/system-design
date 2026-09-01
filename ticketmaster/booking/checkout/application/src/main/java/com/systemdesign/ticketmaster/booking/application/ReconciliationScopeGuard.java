package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;

final class ReconciliationScopeGuard {
    private ReconciliationScopeGuard() {}

    static ReservationCheckout requireMatchingReservation(Booking booking, ReservationCheckout reservation) {
        if (!reservation.id().equals(booking.holdId())
                || !reservation.eventId().equals(booking.eventId())
                || !reservation.userId().equals(booking.userId())
                || !reservation.totalPrice().equals(booking.totalPrice())) {
            throw new IllegalStateException("booking hold scope mismatch for " + booking.id().value());
        }
        if (reservation.status() != ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS) {
            throw new IllegalStateException("booking hold is not in checkout for " + booking.id().value());
        }
        return reservation;
    }
}
