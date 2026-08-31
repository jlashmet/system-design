package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public final class HoldOwnershipException extends RuntimeException {
    private final HoldId holdId;
    private final UserId userId;

    public HoldOwnershipException(HoldId holdId, UserId userId) {
        super("hold " + Objects.requireNonNull(holdId, "holdId").value()
                + " does not belong to user " + Objects.requireNonNull(userId, "userId").value());
        this.holdId = holdId;
        this.userId = userId;
    }

    public HoldId holdId() {
        return holdId;
    }

    public UserId userId() {
        return userId;
    }
}
