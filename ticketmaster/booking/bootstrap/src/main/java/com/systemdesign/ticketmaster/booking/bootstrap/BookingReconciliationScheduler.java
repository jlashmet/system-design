package com.systemdesign.ticketmaster.booking.bootstrap;

import com.systemdesign.ticketmaster.booking.application.ReconcileDueBookingsHandler;
import com.systemdesign.ticketmaster.booking.application.ReconciliationBatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class BookingReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(BookingReconciliationScheduler.class);

    private final ReconcileDueBookingsHandler handler;

    public BookingReconciliationScheduler(ReconcileDueBookingsHandler handler) {
        this.handler = handler;
    }

    @Scheduled(
            initialDelayString = "${ticketmaster.booking.reconciliation-initial-delay-ms:30000}",
            fixedDelayString = "${ticketmaster.booking.reconciliation-poll-delay-ms:5000}")
    public void reconcileDueBookings() {
        ReconciliationBatchResult result = handler.handle();
        if (!result.errors().isEmpty() || !result.failedShards().isEmpty()) {
            log.warn(
                    "Payment reconciliation processed {} bookings with {} booking failures and {} shard-read failures; bookingIds={}, shards={}",
                    result.processed(),
                    result.errors().size(),
                    result.failedShards().size(),
                    result.errors(),
                    result.failedShards());
        } else if (result.processed() > 0) {
            log.info("Payment reconciliation processed {} bookings", result.processed());
        }
    }
}
