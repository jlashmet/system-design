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
        HoldStatus status,
        Instant expiresAt,
        Instant checkoutExpiresAt,
        Instant createdAt) {

    public Hold {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(eventId, "eventId");
        seatIds = Set.copyOf(seatIds);
        if (seatIds.isEmpty()) throw new IllegalArgumentException("hold must contain at least one seat");
        Objects.requireNonNull(totalPrice, "totalPrice");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        if (status == HoldStatus.CHECKOUT_IN_PROGRESS && checkoutExpiresAt == null) {
            throw new IllegalArgumentException("checkout in progress requires a checkout expiration");
        }
    }

    public static Hold active(HoldId id, UserId userId, EventId eventId, Set<SeatId> seatIds,
                              Price totalPrice, Instant createdAt, Instant expiresAt) {
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("hold expiration must be after creation");
        return new Hold(id, userId, eventId, seatIds, totalPrice, HoldStatus.ACTIVE, expiresAt, null, createdAt);
    }

    public boolean isActiveAt(Instant now) {
        return status == HoldStatus.ACTIVE && expiresAt.isAfter(now);
    }

    public Hold startCheckout(Instant now, Instant checkoutDeadline) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(checkoutDeadline, "checkoutDeadline");
        if (!isActiveAt(now)) throw new IllegalStateException("hold is not active");
        if (!checkoutDeadline.isAfter(now)) throw new IllegalArgumentException("checkout deadline must be in the future");
        return new Hold(id, userId, eventId, seatIds, totalPrice, HoldStatus.CHECKOUT_IN_PROGRESS,
                expiresAt, checkoutDeadline, createdAt);
    }

    public Hold convert() {
        if (status != HoldStatus.CHECKOUT_IN_PROGRESS) throw new IllegalStateException("hold is not in checkout");
        return new Hold(id, userId, eventId, seatIds, totalPrice, HoldStatus.CONVERTED,
                expiresAt, checkoutExpiresAt, createdAt);
    }

    public Hold fail() {
        if (status != HoldStatus.CHECKOUT_IN_PROGRESS) throw new IllegalStateException("hold is not in checkout");
        return new Hold(id, userId, eventId, seatIds, totalPrice, HoldStatus.FAILED,
                expiresAt, checkoutExpiresAt, createdAt);
    }
}
