package com.systemdesign.ticketmaster.booking.domain;

public final class CheckoutIdempotencyConflictException extends RuntimeException {
    public CheckoutIdempotencyConflictException(String idempotencyKey) {
        super("checkout idempotency key was reused for a different seat selection: " + idempotencyKey);
    }
}
