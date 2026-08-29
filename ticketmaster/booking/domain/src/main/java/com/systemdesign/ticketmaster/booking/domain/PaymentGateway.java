package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

/**
 * Port to the external payment-intent provider.
 *
 * <p>Implementations translate retryable transport, throttling, timeout, and provider-service
 * failures to {@link PaymentProviderUnavailableException}. Contract/configuration errors should
 * remain distinct failures rather than being mislabeled as transient provider outages.</p>
 */
public interface PaymentGateway {
    PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey);

    /**
     * Event-aware creation used by Booking so a provider-facing service can retain the event ID as
     * routing metadata for verified completion callbacks. Implementations that do not need callback
     * routing may rely on the backward-compatible default.
     */
    default PaymentIntent createPaymentIntent(
            EventId eventId, BookingId bookingId, Price price, String idempotencyKey) {
        Objects.requireNonNull(eventId, "eventId");
        return createPaymentIntent(bookingId, price, idempotencyKey);
    }

    PaymentIntentStatus getPaymentStatus(String paymentIntentId);
    PaymentIntentStatus cancelPaymentIntent(String paymentIntentId);
}
