package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.systemdesign.ticketmaster.booking.domain.PreparedCheckout;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReconcileDueBookingFailureBackoffTest {
    private static final Instant NOW = Instant.parse("2026-08-29T12:30:00Z");
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final BookingId BOOKING_ID = new BookingId("booking-poison");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    private TrackingBookingRepository repository;
    private ReconcileDueBookingsHandler handler;
    private ReconciliationBatchResult result;

    @Test
    void unexpectedPendingFailureIsDeferredOutOfImmediateDuePage() {
        givenPendingBookingWithMissingHold(ignored -> {});
        whenDueBookingsReconciled();
        thenExpectFailureDeferred(NOW.plusSeconds(30));
    }

    @Test
    void wrongRegionFailureDoesNotMutateReconciliationSchedule() {
        givenPendingBookingWithMissingHold(eventId -> {
            throw new WrongBookingRegionException(eventId, "us-east-1", "us-west-2");
        });
        whenDueBookingsReconciled();
        thenExpectFailureNotDeferred();
    }

    private void givenPendingBookingWithMissingHold(EventWriteAuthority authority) {
        Booking booking = pendingBooking();
        repository = new TrackingBookingRepository(booking);
        ReconcileBookingHandler reconcile = new ReconcileBookingHandler(
                authority,
                repository,
                ReservationTestFixtures.service(new MissingHoldRepository()),
                new UnusedCheckoutGateway(),
                new UnusedPaymentGateway(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
        handler = new ReconcileDueBookingsHandler(
                repository,
                reconcile,
                Clock.fixed(NOW, ZoneOffset.UTC),
                1,
                25);
        result = null;
    }

    private void whenDueBookingsReconciled() {
        result = handler.handle();
    }

    private void thenExpectFailureDeferred(Instant expectedNextAttempt) {
        assertThat(result.processed()).isOne();
        assertThat(result.errors()).containsExactly(BOOKING_ID);
        assertThat(result.failedShards()).isEmpty();
        assertThat(repository.rescheduled).isNotNull();
        assertThat(repository.rescheduled.nextReconcileAt()).isEqualTo(expectedNextAttempt);
    }

    private void thenExpectFailureNotDeferred() {
        assertThat(result.processed()).isOne();
        assertThat(result.errors()).containsExactly(BOOKING_ID);
        assertThat(result.failedShards()).isEmpty();
        assertThat(repository.rescheduled).isNull();
    }

    private static Booking pendingBooking() {
        Hold hold = Hold.checkout(
                new HoldId("hold-123"),
                new UserId("user-123"),
                EVENT_ID,
                Set.of(new SeatId("A10")),
                PRICE,
                NOW.minusSeconds(60),
                NOW.plusSeconds(240));
        return Booking.pending(
                        BOOKING_ID,
                        ReservationTestFixtures.from(hold),
                        "checkout-key",
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(1),
                        0)
                .attachPaymentIntent("pi-123");
    }

    private static final class TrackingBookingRepository implements BookingRepository {
        private Booking booking;
        private Booking rescheduled;

        private TrackingBookingRepository(Booking booking) {
            this.booking = booking;
        }

        @Override
        public Optional<Booking> findById(BookingId bookingId) {
            return booking.id().equals(bookingId) ? Optional.of(booking) : Optional.empty();
        }

        @Override
        public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) {
            if (booking.nextReconcileAt() != null && !booking.nextReconcileAt().isAfter(dueAtOrBefore)) {
                return List.of(booking);
            }
            return List.of();
        }

        @Override
        public void rescheduleReconciliation(Booking booking) {
            this.booking = booking;
            this.rescheduled = booking;
        }

        @Override public Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, UserId userId, String key) { return Optional.empty(); }
        @Override public void savePaymentIntent(Booking booking) { throw new AssertionError("not expected"); }
    }

    private static final class MissingHoldRepository implements HoldRepository {
        @Override public Optional<Hold> findById(HoldId holdId) { return Optional.empty(); }
        @Override public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) { throw new AssertionError("not expected"); }
    }

    private static final class UnusedCheckoutGateway implements CheckoutGateway {
        @Override public void startCheckout(PreparedCheckout preparedCheckout, Booking pendingBooking) { throw new AssertionError("not expected"); }
        @Override public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) { throw new AssertionError("not expected"); }
        @Override public void failBooking(ReservationCheckout reservation, Booking failedBooking) { throw new AssertionError("not expected"); }
    }

    private static final class UnusedPaymentGateway implements PaymentGateway {
        @Override public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey) { throw new AssertionError("not expected"); }
        @Override public PaymentIntentStatus getPaymentStatus(String paymentIntentId) { throw new AssertionError("not expected"); }
        @Override public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) { throw new AssertionError("not expected"); }
    }
}
