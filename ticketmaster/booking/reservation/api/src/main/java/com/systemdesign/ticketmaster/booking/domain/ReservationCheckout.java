package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Stable Reservation snapshot used by Checkout without exposing the Hold aggregate. */
public record ReservationCheckout(
        HoldId id,
        UserId userId,
        EventId eventId,
        Set<SeatId> seatIds,
        Price totalPrice,
        ReservationCheckoutStatus status,
        Instant expiresAt,
        Instant checkoutExpiresAt) {

    public ReservationCheckout {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(eventId, "eventId");
        seatIds = Set.copyOf(Objects.requireNonNull(seatIds, "seatIds"));
        if (seatIds.isEmpty()) throw new IllegalArgumentException("reservation must contain at least one seat");
        Objects.requireNonNull(totalPrice, "totalPrice");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (status == ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS && checkoutExpiresAt == null) {
            throw new IllegalArgumentException("checkout reservation requires a checkout expiration");
        }
    }
}
