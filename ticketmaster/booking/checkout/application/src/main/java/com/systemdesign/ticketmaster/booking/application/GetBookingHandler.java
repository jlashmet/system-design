package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingNotFoundException;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.util.Objects;

public final class GetBookingHandler {
    private final BookingRepository bookingRepository;

    public GetBookingHandler(BookingRepository bookingRepository) {
        this.bookingRepository = Objects.requireNonNull(bookingRepository, "bookingRepository");
    }

    public Booking handle(BookingId bookingId, UserId userId) {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(userId, "userId");
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (!booking.userId().equals(userId)) {
            throw new BookingNotFoundException(bookingId);
        }
        return booking;
    }
}
