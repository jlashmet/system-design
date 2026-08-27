package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ReconcileDueBookingsHandler {
    private final BookingRepository bookingRepository;
    private final ReconcileBookingHandler reconcileBookingHandler;
    private final Clock clock;
    private final int reconciliationShards;
    private final int batchSizePerShard;

    public ReconcileDueBookingsHandler(BookingRepository bookingRepository,
                                       ReconcileBookingHandler reconcileBookingHandler,
                                       Clock clock,
                                       int reconciliationShards,
                                       int batchSizePerShard) {
        this.bookingRepository = Objects.requireNonNull(bookingRepository, "bookingRepository");
        this.reconcileBookingHandler = Objects.requireNonNull(reconcileBookingHandler, "reconcileBookingHandler");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (reconciliationShards < 1) throw new IllegalArgumentException("reconciliationShards must be positive");
        if (batchSizePerShard < 1) throw new IllegalArgumentException("batchSizePerShard must be positive");
        this.reconciliationShards = reconciliationShards;
        this.batchSizePerShard = batchSizePerShard;
    }

    public ReconciliationBatchResult handle() {
        Instant dueAtOrBefore = clock.instant();
        int processed = 0;
        List<BookingId> errors = new ArrayList<>();
        for (int shard = 0; shard < reconciliationShards; shard++) {
            List<Booking> due = bookingRepository.findDueForReconciliation(shard, dueAtOrBefore, batchSizePerShard);
            for (Booking booking : due) {
                processed++;
                try {
                    reconcileBookingHandler.handle(booking.id());
                } catch (RuntimeException failure) {
                    errors.add(booking.id());
                }
            }
        }
        return new ReconciliationBatchResult(processed, errors);
    }
}
