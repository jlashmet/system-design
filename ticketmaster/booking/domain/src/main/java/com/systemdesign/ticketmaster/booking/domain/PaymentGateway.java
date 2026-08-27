package com.systemdesign.ticketmaster.booking.domain;

public interface PaymentGateway {
    PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey);
    PaymentIntentStatus getPaymentStatus(String paymentIntentId);
}
