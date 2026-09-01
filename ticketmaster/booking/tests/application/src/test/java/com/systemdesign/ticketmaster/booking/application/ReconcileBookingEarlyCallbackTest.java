package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.UserId;
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

class ReconcileBookingEarlyCallbackTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final BookingId BOOKING_ID = new BookingId("booking-123");
    private static final HoldId HOLD_ID = new HoldId("hold-123");
    private static final Instant NOW = Instant.parse("2026-08-29T01:15:00Z");
    private static final Duration BACKOFF = Duration.ofSeconds(30);
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    private TrackingBookingRepository bookingRepository;
    private TrackingPaymentGateway paymentGateway;
    private ReconcileBookingHandler handler;
    private Booking result;

    @Test
    void earlyProviderCallbackDoesNotPushExistingFallbackPollFurtherOut() {
        givenPendingBookingScheduledAt(NOW.plus(BACKOFF));
        whenProviderCallbackReconciled();
        thenExpectScheduleUnchanged(NOW.plus(BACKOFF));
    }

    @Test
    void dueProviderReconciliationStillMovesFallbackPollForward() {
        givenPendingBookingScheduledAt(NOW.minusSeconds(1));
        whenProviderCallbackReconciled();
        thenExpectScheduleAdvanced(NOW.plus(BACKOFF));
    }

    private void givenPendingBookingScheduledAt(Instant nextReconcileAt) {
        Hold hold = Hold.active(
                        HOLD_ID,
                        new UserId("user-123"),
                        EVENT_ID,
                        Set.of(new SeatId("A10")),
                        PRICE,
                        NOW.minusSeconds(60),
                        NOW.plusSeconds(240))
                .startCheckout(NOW.minusSeconds(30), NOW.plusSeconds(300));
        Booking booking = Booking.pending(
                        BOOKING_ID,
                        ReservationTestFixtures.from(hold),
                        "checkout-key",
                        NOW.minusSeconds(30),
                        nextReconcileAt,
                        0)
                .attachPaymentIntent("pi-123");
        bookingRepository = new TrackingBookingRepository(booking);
        paymentGateway = new TrackingPaymentGateway();
        handler = new ReconcileBookingHandler(
                ignored -> {},
                bookingRepository,
                ReservationTestFixtures.service(new SingleHoldRepository(hold)),
                new UnusedCheckoutGateway(),
                paymentGateway,
                Clock.fixed(NOW, ZoneOffset.UTC),
                BACKOFF);
        result = null;
    }

    private void whenProviderCallbackReconciled() {
        result = handler.handle(EVENT_ID, BOOKING_ID);
    }

    private void thenExpectScheduleUnchanged(Instant expectedNextReconcileAt) {
        assertThat(result.nextReconcileAt()).isEqualTo(expectedNextReconcileAt);
        assertThat(bookingRepository.rescheduleCalls).isZero();
        assertThat(paymentGateway.statusCalls).isOne();
    }

    private void thenExpectScheduleAdvanced(Instant expectedNextReconcileAt) {
        assertThat(result.nextReconcileAt()).isEqualTo(expectedNextReconcileAt);
        assertThat(bookingRepository.rescheduleCalls).isOne();
        assertThat(paymentGateway.statusCalls).isOne();
    }

    private static final class TrackingBookingRepository implements BookingRepository {
        private Booking booking;
        private int rescheduleCalls;

        private TrackingBookingRepository(Booking booking) {
            this.booking = booking;
        }

        @Override
        public Optional<Booking> findById(BookingId bookingId) {
            return booking.id().equals(bookingId) ? Optional.of(booking) : Optional.empty();
        }

        @Override
        public Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, HoldId holdId, String key) {
            return Optional.empty();
        }

        @Override public void savePaymentIntent(Booking booking) { throw new AssertionError("not expected"); }

        @Override
        public void rescheduleReconciliation(Booking booking) {
            rescheduleCalls++;
            this.booking = booking;
        }

        @Override
        public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) {
            return List.of();
        }
    }

    private static final class SingleHoldRepository implements HoldRepository {
        private final Hold hold;

        private SingleHoldRepository(Hold hold) {
            this.hold = hold;
        }

        @Override public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) { throw new AssertionError("not expected"); }
        @Override public void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now, HoldIdempotencyKey key) { throw new AssertionError("not expected"); }
        @Override public Optional<Hold> findById(HoldId holdId) { return Optional.of(hold); }
        @Override public Optional<Hold> findByIdempotencyKey(HoldIdempotencyKey key) { throw new AssertionError("not expected"); }
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

        @Override public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) { throw new AssertionError("not expected"); }
    }

    private static final class UnusedCheckoutGateway implements CheckoutGateway {
        @Override public void startCheckout(ReservationCheckout reservation, Booking pendingBooking) { throw new AssertionError("not expected"); }
        @Override public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) { throw new AssertionError("not expected"); }
        @Override public void failBooking(ReservationCheckout reservation, Booking failedBooking) { throw new AssertionError("not expected"); }
    }
}
