package com.systemdesign.ticketmaster.booking.infrastructure.input;

@FunctionalInterface
public interface VerifiedPaymentStatusChangedHandler {
    void accept(String eventId, String bookingId);
}
