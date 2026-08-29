package com.systemdesign.ticketmaster.booking.infrastructure.input;

import com.systemdesign.ticketmaster.booking.domain.Booking;

@FunctionalInterface
public interface VerifiedPaymentStatusChangedHandler {
    Booking accept(String eventId, String bookingId);
}
