package com.systemdesign.ticketmaster.booking.domain;

public interface CheckoutGateway {
    void startCheckout(PreparedCheckout preparedCheckout, Booking pendingBooking);
    void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking);
    void failBooking(ReservationCheckout reservation, Booking failedBooking);
}
