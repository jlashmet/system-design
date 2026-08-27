package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Objects;

public record EventSeatInventory(
        EventId eventId,
        SeatId seatId,
        Price price,
        SeatStatus status,
        HoldId holdId,
        Instant holdExpiresAt,
        BookingId bookingId) {

    public EventSeatInventory {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(status, "status");
        if (status == SeatStatus.AVAILABLE && (holdId != null || holdExpiresAt != null || bookingId != null)) {
            throw new IllegalArgumentException("available seat cannot have hold or booking state");
        }
        if (status == SeatStatus.HELD && (holdId == null || holdExpiresAt == null || bookingId != null)) {
            throw new IllegalArgumentException("held seat requires hold id and expiration only");
        }
        if (status == SeatStatus.BOOKED && bookingId == null) {
            throw new IllegalArgumentException("booked seat requires booking id");
        }
    }

    public static EventSeatInventory available(EventId eventId, SeatId seatId, Price price) {
        return new EventSeatInventory(eventId, seatId, price, SeatStatus.AVAILABLE, null, null, null);
    }

    public boolean isClaimableAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return status == SeatStatus.AVAILABLE
                || (status == SeatStatus.HELD && !holdExpiresAt.isAfter(now));
    }

    public EventSeatInventory hold(HoldId newHoldId, Instant now, Instant expiresAt) {
        Objects.requireNonNull(newHoldId, "newHoldId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("hold expiration must be in the future");
        if (!isClaimableAt(now)) throw new SeatUnavailableException(seatId);
        return new EventSeatInventory(eventId, seatId, price, SeatStatus.HELD, newHoldId, expiresAt, null);
    }

    public EventSeatInventory book(HoldId expectedHoldId, BookingId newBookingId, Instant now) {
        Objects.requireNonNull(expectedHoldId, "expectedHoldId");
        Objects.requireNonNull(newBookingId, "newBookingId");
        Objects.requireNonNull(now, "now");
        if (status != SeatStatus.HELD || !expectedHoldId.equals(holdId) || !holdExpiresAt.isAfter(now)) {
            throw new SeatUnavailableException(seatId);
        }
        return new EventSeatInventory(eventId, seatId, price, SeatStatus.BOOKED, holdId, null, newBookingId);
    }
}
