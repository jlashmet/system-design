package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository {
    Optional<Booking> findById(BookingId bookingId);
    Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, HoldId holdId, String idempotencyKey);
    void savePaymentIntent(Booking booking);
    void rescheduleReconciliation(Booking booking);
    List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit);
}
