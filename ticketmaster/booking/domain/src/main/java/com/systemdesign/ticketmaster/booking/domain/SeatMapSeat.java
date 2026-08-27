package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public record SeatMapSeat(
        EventId eventId,
        SectionId sectionId,
        SeatId seatId,
        String row,
        String number,
        Price price,
        SeatStatus status) {

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
}
