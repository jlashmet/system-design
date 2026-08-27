package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.BookingId;
import java.util.List;

public record ReconciliationBatchResult(int processed, List<BookingId> errors) {
    public ReconciliationBatchResult {
        if (processed < 0) throw new IllegalArgumentException("processed must not be negative");
        errors = List.copyOf(errors);
    }
}
