package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Objects;

public record SeatMapSeat(
        EventId eventId,
        SectionId sectionId,
        SeatId seatId,
        String row,
        String number,
        Price price,
        SeatStatus status,
        Instant holdExpiresAt) {

    public SeatMapSeat {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(status, "status");
        if (row.isBlank()) throw new IllegalArgumentException("row must not be blank");
        if (number.isBlank()) throw new IllegalArgumentException("number must not be blank");
    }

    public SeatMapSeat(
            EventId eventId,
            SectionId sectionId,
            SeatId seatId,
            String row,
            String number,
            Price price,
            SeatStatus status) {
        this(eventId, sectionId, seatId, row, number, price, status, null);
    }

    /**
     * Returns the user-visible seat state at the supplied time. Ordinary HELD inventory becomes
     * logically available at its deadline even if no cleanup write has occurred. CHECKOUT never
     * time-flips here because payment reconciliation must make release safe first.
     */
    public SeatMapSeat forDisplayAt(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status == SeatStatus.HELD && holdExpiresAt != null && !holdExpiresAt.isAfter(now)) {
            return new SeatMapSeat(eventId, sectionId, seatId, row, number, price, SeatStatus.AVAILABLE, null);
        }
        return this;
    }
}
