package com.systemdesign.ticketmaster.booking.domain;

/**
 * Port to the external payment-intent provider.
 *
 * <p>Implementations translate retryable transport, throttling, timeout, and provider-service
 * failures to {@link PaymentProviderUnavailableException}. Contract/configuration errors should
 * remain distinct failures rather than being mislabeled as transient provider outages.</p>
 */
public interface PaymentGateway {
    PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey);
    PaymentIntentStatus getPaymentStatus(String paymentIntentId);
    PaymentIntentStatus cancelPaymentIntent(String paymentIntentId);
}
