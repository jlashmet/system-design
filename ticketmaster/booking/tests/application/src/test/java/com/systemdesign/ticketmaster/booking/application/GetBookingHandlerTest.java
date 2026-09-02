package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingNotFoundException;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.BookingStatus;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GetBookingHandlerTest {
    private static final BookingId BOOKING_ID = new BookingId("booking-1");
    private static final UserId OWNER = new UserId("user-1");
    private static final Booking BOOKING = new Booking(
            BOOKING_ID,
            OWNER,
            new EventId("event-1"),
            new HoldId("hold-1"),
            BookingStatus.CONFIRMED,
            new Price(new BigDecimal("125.00"), Currency.getInstance("USD")),
            "checkout-key",
            "pi-1",
            null,
            null,
            Instant.parse("2026-09-02T18:00:00Z"));

    @Test
    void returnsBookingToItsOwner() {
        GetBookingHandler handler = new GetBookingHandler(new FakeBookingRepository(Optional.of(BOOKING)));

        assertThat(handler.handle(BOOKING_ID, OWNER)).isEqualTo(BOOKING);
    }

    @Test
    void foreignOwnerLooksExactlyLikeMissingBooking() {
        GetBookingHandler handler = new GetBookingHandler(new FakeBookingRepository(Optional.of(BOOKING)));

        assertThatThrownBy(() -> handler.handle(BOOKING_ID, new UserId("user-other")))
                .isInstanceOf(BookingNotFoundException.class)
                .hasMessage("booking not found: booking-1");
    }

    @Test
    void missingBookingReturnsNotFound() {
        GetBookingHandler handler = new GetBookingHandler(new FakeBookingRepository(Optional.empty()));

        assertThatThrownBy(() -> handler.handle(BOOKING_ID, OWNER))
                .isInstanceOf(BookingNotFoundException.class)
                .hasMessage("booking not found: booking-1");
    }

    private static final class FakeBookingRepository implements BookingRepository {
        private final Optional<Booking> booking;

        private FakeBookingRepository(Optional<Booking> booking) {
            this.booking = booking;
        }

        @Override public Optional<Booking> findById(BookingId bookingId) { return booking; }
        @Override public Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, HoldId holdId, String key) { return Optional.empty(); }
        @Override public void savePaymentIntent(Booking booking) {}
        @Override public void rescheduleReconciliation(Booking booking) {}
        @Override public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) { return List.of(); }
    }
}
