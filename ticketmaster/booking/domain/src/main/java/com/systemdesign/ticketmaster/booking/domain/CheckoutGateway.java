package com.systemdesign.ticketmaster.booking.domain;

public interface CheckoutGateway {
    void startCheckout(Hold checkoutHold, Booking pendingBooking);
    void finalizeBooking(Hold convertedHold, Booking confirmedBooking);
    void failBooking(Hold failedHold, Booking failedBooking);
}
