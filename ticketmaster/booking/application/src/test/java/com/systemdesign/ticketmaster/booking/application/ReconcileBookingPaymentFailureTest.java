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
import com.systemdesign.ticketmaster.booking.domain.PaymentProviderUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.Price;
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

class ReconcileBookingPaymentFailureTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final HoldId HOLD_ID = new HoldId("hold-123");
    private static final BookingId BOOKING_ID = new BookingId("booking-123");
    private static final Instant NOW = Instant.parse("2026-08-28T10:02:00Z");
    private static final Duration BACKOFF = Duration.ofSeconds(30);
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    private TrackingBookingRepository bookingRepository;
    private FailingPaymentGateway paymentGateway;
    private ReconcileBookingHandler handler;
    private Throwable thrown;

    @Test
    void backsOffWhenPaymentIntentCreationFails() {
        given();
        whenBookingReconciled();
        thenExpect(PaymentProviderUnavailableException.class, "payment intent creation", NOW.plus(BACKOFF));
    }

    private void given() {
        Hold active = Hold.active(
                HOLD_ID,
                new UserId("user-456"),
                EVENT_ID,
                Set.of(new SeatId("A10")),
                PRICE,
                NOW.minusSeconds(120),
                NOW.plusSeconds(180));
        Hold checkout = active.startCheckout(NOW.minusSeconds(60), NOW.plusSeconds(240));
        Booking booking = Booking.pending(
                BOOKING_ID,
                checkout,
                "checkout-idempotency",
                NOW.minusSeconds(60),
                NOW.minusSeconds(30),
                0);
        bookingRepository = new TrackingBookingRepository(booking);
        paymentGateway = new FailingPaymentGateway();
        handler = new ReconcileBookingHandler(
                ignored -> {},
                bookingRepository,
                new UnusedHoldRepository(),
                new UnusedCheckoutGateway(),
                paymentGateway,
                Clock.fixed(NOW, ZoneOffset.UTC),
                BACKOFF);
        thrown = null;
    }

    private void whenBookingReconciled() {
        try {
            handler.handle(BOOKING_ID);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpect(
            Class<? extends Throwable> expectedType,
            String expectedOperation,
            Instant expectedNextReconcileAt) {
        assertThat(thrown).isInstanceOf(expectedType);
        assertThat(((PaymentProviderUnavailableException) thrown).operation()).isEqualTo(expectedOperation);
        assertThat(paymentGateway.createCalls).isOne();
        assertThat(bookingRepository.rescheduled).isNotNull();
        assertThat(bookingRepository.rescheduled.nextReconcileAt()).isEqualTo(expectedNextReconcileAt);
        assertThat(bookingRepository.rescheduled.paymentIntentIdOptional()).isEmpty();
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
        public Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, HoldId holdId, String key) {
            return Optional.empty();
        }

        @Override
        public void savePaymentIntent(Booking booking) {
            throw new AssertionError("payment intent must not be saved after provider failure");
        }

        @Override
        public void rescheduleReconciliation(Booking booking) {
            this.booking = booking;
            this.rescheduled = booking;
        }

        @Override
        public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) {
            return List.of();
        }
    }

    private static final class FailingPaymentGateway implements PaymentGateway {
        private int createCalls;

        @Override
        public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey) {
            createCalls++;
            throw new PaymentProviderUnavailableException(
                    "payment intent creation", new RuntimeException("payment provider unavailable"));
        }

        @Override
        public PaymentIntentStatus getPaymentStatus(String paymentIntentId) {
            throw new AssertionError("status lookup must not happen without an intent");
        }

        @Override
        public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) {
            throw new AssertionError("cancellation must not happen without an intent");
        }
    }

    private static final class UnusedHoldRepository implements HoldRepository {
        @Override
        public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) {
            throw new AssertionError("hold access must wait until payment intent exists");
        }

        @Override
        public void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now, HoldIdempotencyKey key) {
            throw new AssertionError("not expected");
        }

        @Override
        public Optional<Hold> findById(HoldId holdId) {
            throw new AssertionError("hold access must wait until payment intent exists");
        }

        @Override
        public Optional<Hold> findByIdempotencyKey(HoldIdempotencyKey key) {
            throw new AssertionError("not expected");
        }
    }

    private static final class UnusedCheckoutGateway implements CheckoutGateway {
        @Override
        public void startCheckout(Hold checkoutHold, Booking pendingBooking) {
            throw new AssertionError("not expected");
        }

        @Override
        public void finalizeBooking(Hold convertedHold, Booking confirmedBooking) {
            throw new AssertionError("not expected");
        }

        @Override
        public void failBooking(Hold failedHold, Booking failedBooking) {
            throw new AssertionError("not expected");
        }
    }
}
