package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public final class CheckoutExpiredException extends RuntimeException {
    private final HoldId holdId;

    public CheckoutExpiredException(HoldId holdId) {
        super("checkout expired for hold " + Objects.requireNonNull(holdId, "holdId").value());
        this.holdId = holdId;
    }

    public HoldId holdId() {
        return holdId;
    }
}
