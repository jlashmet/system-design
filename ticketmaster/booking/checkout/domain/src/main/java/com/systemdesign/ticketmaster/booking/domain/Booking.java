package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record Booking(
        BookingId id,
        UserId userId,
        EventId eventId,
        HoldId holdId,
        State state,
        Price totalPrice,
        String checkoutIdempotencyKey,
        Instant createdAt) {

    public Booking {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(totalPrice, "totalPrice");
        Objects.requireNonNull(checkoutIdempotencyKey, "checkoutIdempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");
        if (checkoutIdempotencyKey.isBlank()) throw new IllegalArgumentException("idempotency key must not be blank");
    }

    public static Booking pending(BookingId id, ReservationCheckout reservation, String checkoutIdempotencyKey,
                                  Instant createdAt, Instant nextReconcileAt, int reconcileShard) {
        Objects.requireNonNull(reservation, "reservation");
        return new Booking(id, reservation.userId(), reservation.eventId(), reservation.id(),
                new PaymentIntentPending(nextReconcileAt, reconcileShard), reservation.totalPrice(),
                checkoutIdempotencyKey, createdAt);
    }

    public BookingStatus status() {
        return switch (state) {
            case PaymentIntentPending ignored, PaymentPending ignored -> BookingStatus.PENDING_PAYMENT;
            case Confirmed ignored -> BookingStatus.CONFIRMED;
            case FailedBeforePaymentIntent ignored, FailedAfterPaymentIntent ignored -> BookingStatus.FAILED;
        };
    }

    public String paymentIntentId() {
        return switch (state) {
            case PaymentPending pending -> pending.paymentIntentId();
            case Confirmed confirmed -> confirmed.paymentIntentId();
            case FailedAfterPaymentIntent failed -> failed.paymentIntentId();
            case PaymentIntentPending ignored, FailedBeforePaymentIntent ignored -> null;
        };
    }

    public Optional<String> paymentIntentIdOptional() {
        return Optional.ofNullable(paymentIntentId());
    }

    public Instant nextReconcileAt() {
        return switch (state) {
            case PaymentIntentPending pending -> pending.nextReconcileAt();
            case PaymentPending pending -> pending.nextReconcileAt();
            case Confirmed ignored, FailedBeforePaymentIntent ignored, FailedAfterPaymentIntent ignored -> null;
        };
    }

    public Integer reconcileShard() {
        return switch (state) {
            case PaymentIntentPending pending -> pending.reconcileShard();
            case PaymentPending pending -> pending.reconcileShard();
            case Confirmed ignored, FailedBeforePaymentIntent ignored, FailedAfterPaymentIntent ignored -> null;
        };
    }

    public Optional<Instant> nextReconcileAtOptional() {
        return Optional.ofNullable(nextReconcileAt());
    }

    public Optional<Integer> reconcileShardOptional() {
        return Optional.ofNullable(reconcileShard());
    }

    public Booking attachPaymentIntent(String intentId) {
        Objects.requireNonNull(intentId, "intentId");
        if (!(state instanceof PaymentIntentPending pending)) {
            throw new IllegalStateException("booking is not awaiting a payment intent");
        }
        return new Booking(id, userId, eventId, holdId,
                new PaymentPending(intentId, pending.nextReconcileAt(), pending.reconcileShard()),
                totalPrice, checkoutIdempotencyKey, createdAt);
    }

    public Booking rescheduleReconciliation(Instant nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (state instanceof PaymentIntentPending pending) {
            requireLater(nextAttemptAt, pending.nextReconcileAt());
            return new Booking(id, userId, eventId, holdId,
                    new PaymentIntentPending(nextAttemptAt, pending.reconcileShard()),
                    totalPrice, checkoutIdempotencyKey, createdAt);
        }
        if (state instanceof PaymentPending pending) {
            requireLater(nextAttemptAt, pending.nextReconcileAt());
            return new Booking(id, userId, eventId, holdId,
                    new PaymentPending(pending.paymentIntentId(), nextAttemptAt, pending.reconcileShard()),
                    totalPrice, checkoutIdempotencyKey, createdAt);
        }
        throw new IllegalStateException("booking is not pending payment");
    }

    public Booking confirm() {
        if (!(state instanceof PaymentPending pending)) {
            throw new IllegalStateException("booking has no confirmed payment intent");
        }
        return new Booking(id, userId, eventId, holdId, new Confirmed(pending.paymentIntentId()),
                totalPrice, checkoutIdempotencyKey, createdAt);
    }

    public Booking fail() {
        State failed = switch (state) {
            case PaymentIntentPending ignored -> new FailedBeforePaymentIntent();
            case PaymentPending pending -> new FailedAfterPaymentIntent(pending.paymentIntentId());
            case Confirmed ignored, FailedBeforePaymentIntent ignored, FailedAfterPaymentIntent ignored ->
                    throw new IllegalStateException("booking is not pending payment");
        };
        return new Booking(id, userId, eventId, holdId, failed, totalPrice, checkoutIdempotencyKey, createdAt);
    }

    private static void requireLater(Instant nextAttemptAt, Instant currentAttemptAt) {
        if (!nextAttemptAt.isAfter(currentAttemptAt)) {
            throw new IllegalArgumentException("next reconciliation attempt must move forward");
        }
    }

    public sealed interface State permits PaymentIntentPending, PaymentPending, Confirmed,
            FailedBeforePaymentIntent, FailedAfterPaymentIntent {
    }

    public record PaymentIntentPending(Instant nextReconcileAt, int reconcileShard) implements State {
        public PaymentIntentPending {
            Objects.requireNonNull(nextReconcileAt, "nextReconcileAt");
            if (reconcileShard < 0) throw new IllegalArgumentException("reconcile shard must not be negative");
        }
    }

    public record PaymentPending(String paymentIntentId, Instant nextReconcileAt, int reconcileShard) implements State {
        public PaymentPending {
            Objects.requireNonNull(paymentIntentId, "paymentIntentId");
            if (paymentIntentId.isBlank()) throw new IllegalArgumentException("payment intent id must not be blank");
            Objects.requireNonNull(nextReconcileAt, "nextReconcileAt");
            if (reconcileShard < 0) throw new IllegalArgumentException("reconcile shard must not be negative");
        }
    }

    public record Confirmed(String paymentIntentId) implements State {
        public Confirmed {
            Objects.requireNonNull(paymentIntentId, "paymentIntentId");
            if (paymentIntentId.isBlank()) throw new IllegalArgumentException("payment intent id must not be blank");
        }
    }

    public record FailedBeforePaymentIntent() implements State {
    }

    public record FailedAfterPaymentIntent(String paymentIntentId) implements State {
        public FailedAfterPaymentIntent {
            Objects.requireNonNull(paymentIntentId, "paymentIntentId");
            if (paymentIntentId.isBlank()) throw new IllegalArgumentException("payment intent id must not be blank");
        }
    }
}
