package com.systemdesign.ticketmaster.booking.domain;

import java.util.Map;
import java.util.Objects;

/**
 * Reservation snapshot plus the authoritative per-seat prices observed immediately
 * before the checkout claim transaction. The checkout transaction rechecks these
 * prices while claiming the seats to close the quote/claim race.
 */
public record PreparedCheckout(ReservationCheckout reservation, Map<SeatId, Price> seatPrices) {
    public PreparedCheckout {
        Objects.requireNonNull(reservation, "reservation");
        seatPrices = Map.copyOf(Objects.requireNonNull(seatPrices, "seatPrices"));
        if (!seatPrices.keySet().equals(reservation.seatIds())) {
            throw new IllegalArgumentException("prepared checkout prices must match reservation seats");
        }
    }
}
