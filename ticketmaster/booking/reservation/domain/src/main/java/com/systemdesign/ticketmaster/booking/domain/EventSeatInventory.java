package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Objects;

public record EventSeatInventory(
        EventId eventId,
        SeatId seatId,
        Price price,
        SeatStatus status,
        HoldId holdId,
        Instant checkoutExpiresAt,
        BookingId bookingId) {

    public EventSeatInventory {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(status, "status");
        if (status == SeatStatus.AVAILABLE && (holdId != null || checkoutExpiresAt != null || bookingId != null)) {
            throw new IllegalArgumentException("available seat cannot have checkout or booking state");
        }
        if (status == SeatStatus.CHECKOUT
                && (holdId == null || checkoutExpiresAt == null || bookingId != null)) {
            throw new IllegalArgumentException("checkout seat requires reservation id and checkout expiration only");
        }
        if (status == SeatStatus.BOOKED && bookingId == null) {
            throw new IllegalArgumentException("booked seat requires booking id");
        }
        if (status == SeatStatus.BOOKED && checkoutExpiresAt != null) {
            throw new IllegalArgumentException("booked seat cannot have a checkout expiration");
        }
    }

    public static EventSeatInventory available(EventId eventId, SeatId seatId, Price price) {
        return new EventSeatInventory(eventId, seatId, price, SeatStatus.AVAILABLE, null, null, null);
    }

    public boolean isClaimableAt(Instant now) {
        Objects.requireNonNull(now, "now");
        // CHECKOUT inventory is never reclaimed purely from the wall clock. After the
        // customer deadline, payment reconciliation must first prove that releasing
        // the seat cannot race with a successful charge.
        return status == SeatStatus.AVAILABLE;
    }

    public EventSeatInventory startCheckout(HoldId newHoldId, Instant now, Instant checkoutDeadline) {
        Objects.requireNonNull(newHoldId, "newHoldId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(checkoutDeadline, "checkoutDeadline");
        if (!checkoutDeadline.isAfter(now)) {
            throw new IllegalArgumentException("checkout expiration must be in the future");
        }
        if (status != SeatStatus.AVAILABLE) throw new SeatUnavailableException(seatId);
        return new EventSeatInventory(eventId, seatId, price, SeatStatus.CHECKOUT,
                newHoldId, checkoutDeadline, null);
    }

    public EventSeatInventory book(HoldId expectedHoldId, BookingId newBookingId) {
        Objects.requireNonNull(expectedHoldId, "expectedHoldId");
        Objects.requireNonNull(newBookingId, "newBookingId");
        if (status != SeatStatus.CHECKOUT || !expectedHoldId.equals(holdId)) {
            throw new SeatUnavailableException(seatId);
        }
        return new EventSeatInventory(eventId, seatId, price, SeatStatus.BOOKED, holdId, null, newBookingId);
    }

    public EventSeatInventory releaseCheckout(HoldId expectedHoldId) {
        Objects.requireNonNull(expectedHoldId, "expectedHoldId");
        if (status != SeatStatus.CHECKOUT || !expectedHoldId.equals(holdId)) {
            throw new SeatUnavailableException(seatId);
        }
        return available(eventId, seatId, price);
    }
}
