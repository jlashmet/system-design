package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record Booking(
        BookingId id,
        UserId userId,
        EventId eventId,
        HoldId holdId,
        BookingStatus status,
        Price totalPrice,
        String checkoutIdempotencyKey,
        String paymentIntentId,
        Instant nextReconcileAt,
        Integer reconcileShard,
        Instant createdAt) {

    public Booking {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(totalPrice, "totalPrice");
        Objects.requireNonNull(checkoutIdempotencyKey, "checkoutIdempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");
        if (checkoutIdempotencyKey.isBlank()) throw new IllegalArgumentException("idempotency key must not be blank");
        if (status == BookingStatus.PENDING_PAYMENT && (nextReconcileAt == null || reconcileShard == null)) {
            throw new IllegalArgumentException("pending booking must be scheduled for reconciliation");
        }
        if (reconcileShard != null && reconcileShard < 0) throw new IllegalArgumentException("reconcile shard must not be negative");
    }

    public static Booking pending(BookingId id, Hold hold, String checkoutIdempotencyKey,
                                  Instant createdAt, Instant nextReconcileAt, int reconcileShard) {
        return new Booking(id, hold.userId(), hold.eventId(), hold.id(), BookingStatus.PENDING_PAYMENT,
                hold.totalPrice(), checkoutIdempotencyKey, null, nextReconcileAt, reconcileShard, createdAt);
    }

    public Optional<String> paymentIntentIdOptional() {
        return Optional.ofNullable(paymentIntentId);
    }

    public Booking attachPaymentIntent(String intentId) {
        Objects.requireNonNull(intentId, "intentId");
        if (status != BookingStatus.PENDING_PAYMENT) throw new IllegalStateException("booking is not pending payment");
        return new Booking(id, userId, eventId, holdId, status, totalPrice, checkoutIdempotencyKey,
                intentId, nextReconcileAt, reconcileShard, createdAt);
    }

    public Booking rescheduleReconciliation(Instant nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (status != BookingStatus.PENDING_PAYMENT) throw new IllegalStateException("booking is not pending payment");
        if (!nextAttemptAt.isAfter(nextReconcileAt)) {
            throw new IllegalArgumentException("next reconciliation attempt must move forward");
        }
        return new Booking(id, userId, eventId, holdId, status, totalPrice, checkoutIdempotencyKey,
                paymentIntentId, nextAttemptAt, reconcileShard, createdAt);
    }

    public Booking confirm() {
        if (status != BookingStatus.PENDING_PAYMENT) throw new IllegalStateException("booking is not pending payment");
        return new Booking(id, userId, eventId, holdId, BookingStatus.CONFIRMED, totalPrice,
                checkoutIdempotencyKey, paymentIntentId, null, null, createdAt);
    }

    public Booking fail() {
        if (status != BookingStatus.PENDING_PAYMENT) throw new IllegalStateException("booking is not pending payment");
        return new Booking(id, userId, eventId, holdId, BookingStatus.FAILED, totalPrice,
                checkoutIdempotencyKey, paymentIntentId, null, null, createdAt);
    }
}
