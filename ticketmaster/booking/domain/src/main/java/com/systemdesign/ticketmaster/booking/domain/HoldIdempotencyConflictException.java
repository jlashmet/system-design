package com.systemdesign.ticketmaster.booking.domain;

public final class HoldIdempotencyConflictException extends RuntimeException {
    public HoldIdempotencyConflictException(HoldIdempotencyKey key) {
        super("idempotency key " + key.value() + " was already used for a different hold request");
    }
}
