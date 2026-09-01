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

class ReconcileBookingPaymentPersistenceFailureTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final HoldId HOLD_ID = new HoldId("hold-123");
    private static final BookingId BOOKING_ID = new BookingId("booking-123");
    private static final Instant NOW = Instant.parse("2026-08-28T10:02:00Z");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    private FailingBookingRepository bookingRepository;
    private SuccessfulPaymentGateway paymentGateway;
    private ReconcileBookingHandler handler;
    private Throwable thrown;

    @Test
    void doesNotMisclassifyPaymentIntentPersistenceFailureAsProviderFailure() {
        given();
        whenBookingReconciled();
        thenExpect(RuntimeException.class, 0);
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
                ReservationTestFixtures.from(checkout),
                "checkout-idempotency",
                NOW.minusSeconds(60),
                NOW.minusSeconds(30),
                0);
        bookingRepository = new FailingBookingRepository(booking);
        paymentGateway = new SuccessfulPaymentGateway();
        handler = new ReconcileBookingHandler(
                ignored -> {},
                bookingRepository,
                ReservationTestFixtures.service(new MatchingHoldRepository(checkout)),
                new UnusedCheckoutGateway(),
                paymentGateway,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
        thrown = null;
    }

    private void whenBookingReconciled() {
        try {
            handler.handle(BOOKING_ID);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpect(Class<? extends Throwable> expectedType, int expectedRescheduleCalls) {
        assertThat(thrown).isInstanceOf(expectedType);
        assertThat(paymentGateway.createCalls).isOne();
        assertThat(bookingRepository.saveIntentCalls).isOne();
        assertThat(bookingRepository.rescheduleCalls).isEqualTo(expectedRescheduleCalls);
    }

    private static final class FailingBookingRepository implements BookingRepository {
        private final Booking booking;
        private int saveIntentCalls;
        private int rescheduleCalls;

        private FailingBookingRepository(Booking booking) {
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
            saveIntentCalls++;
            throw new RuntimeException("booking storage unavailable");
        }

        @Override
        public void rescheduleReconciliation(Booking booking) {
            rescheduleCalls++;
        }

        @Override
        public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) {
            return List.of();
        }
    }

    private static final class SuccessfulPaymentGateway implements PaymentGateway {
        private int createCalls;

        @Override
        public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey) {
            createCalls++;
            return new PaymentIntent("pi-123", PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
        }

        @Override
        public PaymentIntentStatus getPaymentStatus(String paymentIntentId) {
            throw new AssertionError("status lookup must wait until intent persistence succeeds");
        }

        @Override
        public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) {
            throw new AssertionError("not expected");
        }
    }

    private static final class MatchingHoldRepository implements HoldRepository {
        private final Hold hold;

        private MatchingHoldRepository(Hold hold) {
            this.hold = hold;
        }

        @Override
        public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) {
            throw new AssertionError("not expected");
        }

        @Override
        public void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now, HoldIdempotencyKey key) {
            throw new AssertionError("not expected");
        }

        @Override
        public Optional<Hold> findById(HoldId holdId) {
            return hold.id().equals(holdId) ? Optional.of(hold) : Optional.empty();
        }

        @Override
        public Optional<Hold> findByIdempotencyKey(HoldIdempotencyKey key) {
            throw new AssertionError("not expected");
        }
    }

    private static final class UnusedCheckoutGateway implements CheckoutGateway {
        @Override public void startCheckout(ReservationCheckout reservation, Booking pendingBooking) { throw new AssertionError("not expected"); }
        @Override public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) { throw new AssertionError("not expected"); }
        @Override public void failBooking(ReservationCheckout reservation, Booking failedBooking) { throw new AssertionError("not expected"); }
    }
}
