package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Objects;

public record EventSeatInventory(
        EventId eventId,
        SeatId seatId,
        Price price,
        State state) {

    public EventSeatInventory {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(state, "state");
    }

    public static EventSeatInventory available(EventId eventId, SeatId seatId, Price price) {
        return new EventSeatInventory(eventId, seatId, price, new Available());
    }

    public SeatStatus status() {
        return switch (state) {
            case Available ignored -> SeatStatus.AVAILABLE;
            case Held ignored -> SeatStatus.HELD;
            case Checkout ignored -> SeatStatus.CHECKOUT;
            case Booked ignored -> SeatStatus.BOOKED;
        };
    }

    public HoldId holdId() {
        return switch (state) {
            case Held held -> held.holdId();
            case Checkout checkout -> checkout.holdId();
            case Available ignored -> null;
            case Booked ignored -> null;
        };
    }

    public Instant holdExpiresAt() {
        return switch (state) {
            case Held held -> held.expiresAt();
            case Checkout checkout -> checkout.expiresAt();
            case Available ignored -> null;
            case Booked ignored -> null;
        };
    }

    public BookingId bookingId() {
        return state instanceof Booked booked ? booked.bookingId() : null;
    }

    public boolean isClaimableAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return switch (state) {
            case Available ignored -> true;
            case Held held -> !held.expiresAt().isAfter(now);
            case Checkout ignored -> false;
            case Booked ignored -> false;
        };
    }

    public EventSeatInventory hold(HoldId newHoldId, Instant now, Instant expiresAt) {
        Objects.requireNonNull(newHoldId, "newHoldId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("hold expiration must be in the future");
        if (!isClaimableAt(now)) throw new SeatUnavailableException(seatId);
        return new EventSeatInventory(eventId, seatId, price, new Held(newHoldId, expiresAt));
    }

    public EventSeatInventory startCheckout(HoldId expectedHoldId, Instant now, Instant checkoutExpiresAt) {
        Objects.requireNonNull(expectedHoldId, "expectedHoldId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(checkoutExpiresAt, "checkoutExpiresAt");
        if (!(state instanceof Held held)
                || !expectedHoldId.equals(held.holdId())
                || !held.expiresAt().isAfter(now)) {
            throw new SeatUnavailableException(seatId);
        }
        if (!checkoutExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("checkout expiration must be in the future");
        }
        return new EventSeatInventory(eventId, seatId, price, new Checkout(held.holdId(), checkoutExpiresAt));
    }

    public EventSeatInventory book(HoldId expectedHoldId, BookingId newBookingId) {
        Objects.requireNonNull(expectedHoldId, "expectedHoldId");
        Objects.requireNonNull(newBookingId, "newBookingId");
        if (!(state instanceof Checkout checkout) || !expectedHoldId.equals(checkout.holdId())) {
            throw new SeatUnavailableException(seatId);
        }
        return new EventSeatInventory(eventId, seatId, price, new Booked(newBookingId));
    }

    public EventSeatInventory releaseCheckout(HoldId expectedHoldId) {
        Objects.requireNonNull(expectedHoldId, "expectedHoldId");
        if (!(state instanceof Checkout checkout) || !expectedHoldId.equals(checkout.holdId())) {
            throw new SeatUnavailableException(seatId);
        }
        return available(eventId, seatId, price);
    }

    public sealed interface State permits Available, Held, Checkout, Booked {
    }

    public record Available() implements State {
    }

    public record Held(HoldId holdId, Instant expiresAt) implements State {
        public Held {
            Objects.requireNonNull(holdId, "holdId");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record Checkout(HoldId holdId, Instant expiresAt) implements State {
        public Checkout {
            Objects.requireNonNull(holdId, "holdId");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record Booked(BookingId bookingId) implements State {
        public Booked {
            Objects.requireNonNull(bookingId, "bookingId");
        }
    }
}
