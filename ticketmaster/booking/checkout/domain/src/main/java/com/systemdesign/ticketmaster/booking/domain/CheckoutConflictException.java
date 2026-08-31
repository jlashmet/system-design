package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public final class CheckoutConflictException extends RuntimeException {
    private final HoldId holdId;

    public CheckoutConflictException(HoldId holdId, Throwable cause) {
        super("checkout state changed concurrently for hold " + Objects.requireNonNull(holdId, "holdId").value(), cause);
        this.holdId = holdId;
    }

    public HoldId holdId() {
        return holdId;
    }
}
