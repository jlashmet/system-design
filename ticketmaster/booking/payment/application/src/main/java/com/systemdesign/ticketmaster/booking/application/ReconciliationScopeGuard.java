package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldStatus;

final class ReconciliationScopeGuard {
    private ReconciliationScopeGuard() {}

    static Hold requireMatchingHold(Booking booking, Hold hold) {
        if (!hold.id().equals(booking.holdId())
                || !hold.eventId().equals(booking.eventId())
                || !hold.userId().equals(booking.userId())
                || !hold.totalPrice().equals(booking.totalPrice())) {
            throw new IllegalStateException("booking hold scope mismatch for " + booking.id().value());
        }
        if (hold.status() != HoldStatus.CHECKOUT_IN_PROGRESS) {
            throw new IllegalStateException("booking hold is not in checkout for " + booking.id().value());
        }
        return hold;
    }
}
