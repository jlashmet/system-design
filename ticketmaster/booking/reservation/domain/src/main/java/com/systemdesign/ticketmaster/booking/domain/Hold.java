package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record Hold(
        HoldId id,
        UserId userId,
        EventId eventId,
        Set<SeatId> seatIds,
        Price totalPrice,
        State state,
        Instant expiresAt,
        Instant createdAt) {

    public Hold {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(eventId, "eventId");
        seatIds = Set.copyOf(seatIds);
        if (seatIds.isEmpty()) throw new IllegalArgumentException("hold must contain at least one seat");
        Objects.requireNonNull(totalPrice, "totalPrice");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static Hold active(HoldId id, UserId userId, EventId eventId, Set<SeatId> seatIds,
                              Price totalPrice, Instant createdAt, Instant expiresAt) {
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("hold expiration must be after creation");
        return new Hold(id, userId, eventId, seatIds, totalPrice, new Active(), expiresAt, createdAt);
    }

    public HoldStatus status() {
        return switch (state) {
            case Active ignored -> HoldStatus.ACTIVE;
            case CheckoutInProgress ignored -> HoldStatus.CHECKOUT_IN_PROGRESS;
            case Converted ignored -> HoldStatus.CONVERTED;
            case Failed ignored -> HoldStatus.FAILED;
        };
    }

    public Instant checkoutExpiresAt() {
        return state instanceof CheckoutInProgress checkout ? checkout.expiresAt() : null;
    }

    public boolean isActiveAt(Instant now) {
        return state instanceof Active && expiresAt.isAfter(now);
    }

    public Hold startCheckout(Instant now, Instant checkoutDeadline) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(checkoutDeadline, "checkoutDeadline");
        if (!isActiveAt(now)) throw new IllegalStateException("hold is not active");
        if (!checkoutDeadline.isAfter(now)) throw new IllegalArgumentException("checkout deadline must be in the future");
        return new Hold(id, userId, eventId, seatIds, totalPrice,
                new CheckoutInProgress(checkoutDeadline), expiresAt, createdAt);
    }

    public Hold convert() {
        if (!(state instanceof CheckoutInProgress)) throw new IllegalStateException("hold is not in checkout");
        return new Hold(id, userId, eventId, seatIds, totalPrice, new Converted(), expiresAt, createdAt);
    }

    public Hold fail() {
        if (!(state instanceof CheckoutInProgress)) throw new IllegalStateException("hold is not in checkout");
        return new Hold(id, userId, eventId, seatIds, totalPrice, new Failed(), expiresAt, createdAt);
    }

    public sealed interface State permits Active, CheckoutInProgress, Converted, Failed {
    }

    public record Active() implements State {
    }

    public record CheckoutInProgress(Instant expiresAt) implements State {
        public CheckoutInProgress {
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record Converted() implements State {
    }

    public record Failed() implements State {
    }
}
