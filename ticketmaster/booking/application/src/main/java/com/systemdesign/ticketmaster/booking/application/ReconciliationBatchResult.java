package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.BookingId;
import java.util.List;

public record ReconciliationBatchResult(int processed, List<BookingId> errors, List<Integer> failedShards) {
    public ReconciliationBatchResult {
        if (processed < 0) throw new IllegalArgumentException("processed must not be negative");
        errors = List.copyOf(errors);
        failedShards = List.copyOf(failedShards);
    }

    public ReconciliationBatchResult(int processed, List<BookingId> errors) {
        this(processed, errors, List.of());
    }
}
