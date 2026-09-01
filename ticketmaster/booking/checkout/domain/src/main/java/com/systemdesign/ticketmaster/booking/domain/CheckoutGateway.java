package com.systemdesign.ticketmaster.booking.domain;

public interface CheckoutGateway {
    void startCheckout(ReservationCheckout reservation, Booking pendingBooking);
    void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking);
    void failBooking(ReservationCheckout reservation, Booking failedBooking);
}
