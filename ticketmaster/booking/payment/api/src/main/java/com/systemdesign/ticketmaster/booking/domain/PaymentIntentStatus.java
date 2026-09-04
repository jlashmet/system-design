package com.systemdesign.ticketmaster.booking.domain;

/**
 * Status of the logical payment intent for a checkout.
 *
 * <p>A declined card is recoverable and must be represented as
 * {@link #REQUIRES_PAYMENT_METHOD}; the customer may retry the same intent with a different
 * payment method while the checkout deadline remains open. {@link #CANCELED} is terminal and
 * means the provider guarantees that this intent can no longer produce a charge.</p>
 */
public enum PaymentIntentStatus {
    REQUIRES_PAYMENT_METHOD,
    PROCESSING,
    SUCCEEDED,
    CANCELED
}
