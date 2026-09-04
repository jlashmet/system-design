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

class ReconcileBookingEventScopeTest {
    private static final EventId ACTUAL_EVENT = new EventId("event-1");
    private static final EventId OTHER_EVENT = new EventId("event-2");
    private static final BookingId BOOKING_ID = new BookingId("booking-1");
    private static final Instant NOW = Instant.parse("2026-08-28T18:00:00Z");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    private TrackingBookingRepository repository;
    private TrackingPaymentGateway paymentGateway;
    private ReconcileBookingHandler handler;
    private Throwable thrown;

    @Test
    void scopedCallbackChecksRegionBeforeReadingRegionalBookingState() {
        givenWrongBookingRegion();
        whenScopedReconcile(ACTUAL_EVENT);
        thenExpectWrongRegionBeforeBookingRead();
    }

    @Test
    void scopedCallbackRejectsBookingFromDifferentEventBeforeProviderAccess() {
        givenLocalRegionWithPendingBooking();
        whenScopedReconcile(OTHER_EVENT);
        thenExpectEventMismatchBeforeProviderAccess();
    }

    private void givenWrongBookingRegion() {
        repository = new TrackingBookingRepository(pendingBooking());
        paymentGateway = new TrackingPaymentGateway();
        EventWriteAuthority wrongRegion = eventId -> {
            throw new WrongBookingRegionException(eventId, "us-east-1", "us-west-2");
        };
        handler = handler(wrongRegion, repository, paymentGateway);
        thrown = null;
    }

    private void givenLocalRegionWithPendingBooking() {
        repository = new TrackingBookingRepository(pendingBooking());
        paymentGateway = new TrackingPaymentGateway();
        handler = handler(ignored -> {}, repository, paymentGateway);
        thrown = null;
    }

    private void whenScopedReconcile(EventId eventId) {
        try {
            handler.handle(eventId, BOOKING_ID);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectWrongRegionBeforeBookingRead() {
        assertThat(thrown).isInstanceOf(WrongBookingRegionException.class);
        assertThat(repository.findByIdCalls).isZero();
    }

    private void thenExpectEventMismatchBeforeProviderAccess() {
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to event");
        assertThat(repository.findByIdCalls).isOne();
        assertThat(paymentGateway.statusCalls).isZero();
    }

    private static ReconcileBookingHandler handler(
            EventWriteAuthority authority,
            TrackingBookingRepository repository,
            TrackingPaymentGateway paymentGateway) {
        return new ReconcileBookingHandler(
                authority,
                repository,
                ReservationTestFixtures.service(new UnusedHoldRepository()),
                new UnusedCheckoutGateway(),
                paymentGateway,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
    }

    private static Booking pendingBooking() {
        Hold hold = Hold.checkout(
                new HoldId("hold-1"),
                new UserId("user-1"),
                ACTUAL_EVENT,
                Set.of(new SeatId("A10")),
                PRICE,
                NOW.minusSeconds(30),
                NOW.plusSeconds(300));
        return Booking.pending(
                        BOOKING_ID,
                        ReservationTestFixtures.from(hold),
                        "checkout-key",
                        NOW.minusSeconds(30),
                        NOW.plusSeconds(30),
                        0)
                .attachPaymentIntent("pi-1");
    }

    private static final class TrackingBookingRepository implements BookingRepository {
        private final Booking booking;
        private int findByIdCalls;

        private TrackingBookingRepository(Booking booking) {
            this.booking = booking;
        }

        @Override
        public Optional<Booking> findById(BookingId bookingId) {
            findByIdCalls++;
            return booking.id().equals(bookingId) ? Optional.of(booking) : Optional.empty();
        }

        @Override
        public Optional<Booking> findByCheckoutIdempotencyKey(
                EventId eventId, UserId userId, String idempotencyKey) {
            return Optional.empty();
        }

        @Override public void savePaymentIntent(Booking booking) { throw new AssertionError("not expected"); }
        @Override public void rescheduleReconciliation(Booking booking) { throw new AssertionError("not expected"); }
        @Override public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) {
            return List.of();
        }
    }

    private static final class TrackingPaymentGateway implements PaymentGateway {
        private int statusCalls;

        @Override
        public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey) {
            throw new AssertionError("not expected");
        }

        @Override
        public PaymentIntentStatus getPaymentStatus(String paymentIntentId) {
            statusCalls++;
            return PaymentIntentStatus.PROCESSING;
        }

        @Override
        public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) {
            throw new AssertionError("not expected");
        }
    }

    private static final class UnusedHoldRepository implements HoldRepository {
        @Override public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) { throw new AssertionError("not expected"); }
        @Override public Optional<Hold> findById(HoldId holdId) { throw new AssertionError("not expected"); }
    }

    private static final class UnusedCheckoutGateway implements CheckoutGateway {
        @Override public void startCheckout(PreparedCheckout preparedCheckout, Booking pendingBooking) { throw new AssertionError("not expected"); }
        @Override public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) { throw new AssertionError("not expected"); }
        @Override public void failBooking(ReservationCheckout reservation, Booking failedBooking) { throw new AssertionError("not expected"); }
    }
}
