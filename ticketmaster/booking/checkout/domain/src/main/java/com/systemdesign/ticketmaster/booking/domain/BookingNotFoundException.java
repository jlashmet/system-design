package com.systemdesign.ticketmaster.booking.domain;

public final class BookingNotFoundException extends RuntimeException {
    private final BookingId bookingId;

    public BookingNotFoundException(BookingId bookingId) {
        super("booking not found: " + bookingId.value());
        this.bookingId = bookingId;
    }

    public BookingId bookingId() {
        return bookingId;
    }
}
