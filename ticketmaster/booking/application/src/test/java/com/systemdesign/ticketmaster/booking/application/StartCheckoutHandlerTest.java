package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StartCheckoutHandlerTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final HoldId HOLD_ID = new HoldId("hold-1");
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    @Test
    void rejectsWrongRegionBeforeReadingCheckoutState() {
        TrackingBookingRepository bookingRepository = new TrackingBookingRepository();
        EventWriteAuthority wrongRegion = eventId -> {
            throw new WrongBookingRegionException(eventId, "us-east-1", "us-west-2");
        };
        StartCheckoutHandler handler = new StartCheckoutHandler(
                wrongRegion,
                new UnusedHoldRepository(),
                bookingRepository,
                new UnusedCheckoutGateway(),
                new UnusedPaymentGateway(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                16);

        assertThatThrownBy(() -> handler.handle(new StartCheckoutCommand(EVENT_ID, HOLD_ID, "idem-1")))
                .isInstanceOf(WrongBookingRegionException.class);

        assertThat(bookingRepository.idempotencyReads).isZero();
    }

    private static final class TrackingBookingRepository implements BookingRepository {
        private int idempotencyReads;

        @Override public Optional<Booking> findById(BookingId bookingId) { return Optional.empty(); }
        @Override
        public Optional<Booking> findByCheckoutIdempotencyKey(
                EventId eventId, HoldId holdId, String idempotencyKey) {
            idempotencyReads++;
            return Optional.empty();
        }
        @Override public void savePaymentIntent(Booking booking) { throw new AssertionError("not expected"); }
        @Override public void rescheduleReconciliation(Booking booking) { throw new AssertionError("not expected"); }
        @Override public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) {
            return List.of();
        }
    }

    private static final class UnusedHoldRepository implements HoldRepository {
        @Override public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) {
            throw new AssertionError("not expected");
        }
        @Override public void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now) {
            throw new AssertionError("not expected");
        }
        @Override public Optional<Hold> findById(HoldId holdId) {
            throw new AssertionError("not expected");
        }
    }

    private static final class UnusedCheckoutGateway implements CheckoutGateway {
        @Override public void startCheckout(Hold checkoutHold, Booking pendingBooking) { throw new AssertionError("not expected"); }
        @Override public void finalizeBooking(Hold convertedHold, Booking confirmedBooking) { throw new AssertionError("not expected"); }
        @Override public void failBooking(Hold failedHold, Booking failedBooking) { throw new AssertionError("not expected"); }
    }

    private static final class UnusedPaymentGateway implements PaymentGateway {
        @Override public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey) {
            throw new AssertionError("not expected");
        }
        @Override public PaymentIntentStatus getPaymentStatus(String paymentIntentId) { throw new AssertionError("not expected"); }
        @Override public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) { throw new AssertionError("not expected"); }
    }
}
