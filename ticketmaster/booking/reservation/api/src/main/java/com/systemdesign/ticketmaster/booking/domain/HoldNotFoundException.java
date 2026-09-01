package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public final class HoldNotFoundException extends RuntimeException {
    private final HoldId holdId;

    public HoldNotFoundException(HoldId holdId) {
        super("hold not found: " + Objects.requireNonNull(holdId, "holdId").value());
        this.holdId = holdId;
    }

    public HoldId holdId() {
        return holdId;
    }
}
