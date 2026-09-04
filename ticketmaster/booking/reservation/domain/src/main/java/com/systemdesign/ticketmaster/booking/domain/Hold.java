package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Reservation record created when the customer enters checkout. There is no
 * pre-checkout ACTIVE phase: seat selection remains client-side until the
 * customer clicks Next, which creates this reservation with one fixed deadline.
 */
public record Hold(
        HoldId id,
        UserId userId,
        EventId eventId,
        Set<SeatId> seatIds,
        Price totalPrice,
        HoldStatus status,
        Instant checkoutExpiresAt,
        Instant createdAt) {

    public Hold {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(eventId, "eventId");
        seatIds = Set.copyOf(Objects.requireNonNull(seatIds, "seatIds"));
        if (seatIds.isEmpty()) throw new IllegalArgumentException("checkout reservation must contain at least one seat");
        Objects.requireNonNull(totalPrice, "totalPrice");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(checkoutExpiresAt, "checkoutExpiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!checkoutExpiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("checkout expiration must be after creation");
        }
    }

    public static Hold checkout(HoldId id, UserId userId, EventId eventId, Set<SeatId> seatIds,
                                Price totalPrice, Instant createdAt, Instant checkoutExpiresAt) {
        return new Hold(id, userId, eventId, seatIds, totalPrice, HoldStatus.CHECKOUT_IN_PROGRESS,
                checkoutExpiresAt, createdAt);
    }

    public boolean isCheckoutActiveAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return status == HoldStatus.CHECKOUT_IN_PROGRESS && checkoutExpiresAt.isAfter(now);
    }

    public Hold convert() {
        if (status != HoldStatus.CHECKOUT_IN_PROGRESS) throw new IllegalStateException("reservation is not in checkout");
        return new Hold(id, userId, eventId, seatIds, totalPrice, HoldStatus.CONVERTED,
                checkoutExpiresAt, createdAt);
    }

    public Hold fail() {
        if (status != HoldStatus.CHECKOUT_IN_PROGRESS) throw new IllegalStateException("reservation is not in checkout");
        return new Hold(id, userId, eventId, seatIds, totalPrice, HoldStatus.FAILED,
                checkoutExpiresAt, createdAt);
    }
}
