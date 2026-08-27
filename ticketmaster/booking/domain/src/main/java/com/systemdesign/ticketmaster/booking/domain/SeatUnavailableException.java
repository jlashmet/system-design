package com.systemdesign.ticketmaster.booking.domain;

public final class SeatUnavailableException extends RuntimeException {
    public SeatUnavailableException(SeatId seatId) {
        super("seat is unavailable: " + seatId.value());
    }
}
