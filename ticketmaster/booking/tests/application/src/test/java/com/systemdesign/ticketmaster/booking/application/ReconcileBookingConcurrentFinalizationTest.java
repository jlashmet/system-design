package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.BookingStatus;
import com.systemdesign.ticketmaster.booking.domain.CheckoutConflictException;
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

class ReconcileBookingConcurrentFinalizationTest {
    private static final Instant NOW = Instant.parse("2026-08-29T01:35:00Z");
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final HoldId HOLD_ID = new HoldId("hold-123");
    private static final BookingId BOOKING_ID = new BookingId("booking-123");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    private MutableBookingRepository repository;
    private ReconcileBookingHandler handler;
    private Booking result;
    private Throwable thrown;

    @Test
    void concurrentConfirmationIsIdempotent() {
        givenConcurrentWinner(PaymentIntentStatus.SUCCEEDED, BookingStatus.CONFIRMED);
        whenBookingReconciled();
        thenExpectTerminalResult(BookingStatus.CONFIRMED);
    }

    @Test
    void concurrentFailureIsIdempotent() {
        givenConcurrentWinner(PaymentIntentStatus.CANCELED, BookingStatus.FAILED);
        whenBookingReconciled();
        thenExpectTerminalResult(BookingStatus.FAILED);
    }

    @Test
    void contradictoryConcurrentFinalizationRemainsConflict() {
        givenConcurrentWinner(PaymentIntentStatus.SUCCEEDED, BookingStatus.FAILED);
        whenBookingReconciled();
        thenExpectConflict();
    }

    private void givenConcurrentWinner(PaymentIntentStatus providerStatus, BookingStatus winnerStatus) {
        Hold hold = checkoutHold();
        Booking booking = Booking.pending(
                        BOOKING_ID,
                        ReservationTestFixtures.from(hold),
                        "checkout-key",
                        NOW.minusSeconds(30),
                        NOW.plusSeconds(30),
                        0)
                .attachPaymentIntent("pi-123");
        repository = new MutableBookingRepository(booking);
        handler = new ReconcileBookingHandler(
                ignored -> {},
                repository,
                ReservationTestFixtures.service(new FixedHoldRepository(hold)),
                new RacingCheckoutGateway(repository, winnerStatus),
                new FixedPaymentGateway(providerStatus),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
        result = null;
        thrown = null;
    }

    private void whenBookingReconciled() {
        try {
            result = handler.handle(BOOKING_ID);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectTerminalResult(BookingStatus expectedStatus) {
        assertThat(thrown).isNull();
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(repository.findByIdCalls).isEqualTo(2);
    }

    private void thenExpectConflict() {
        assertThat(thrown).isInstanceOf(CheckoutConflictException.class);
        assertThat(result).isNull();
        assertThat(repository.booking.status()).isEqualTo(BookingStatus.FAILED);
        assertThat(repository.findByIdCalls).isEqualTo(2);
    }

    private static Hold checkoutHold() {
        return Hold.active(
                        HOLD_ID,
                        new UserId("user-123"),
                        EVENT_ID,
                        Set.of(new SeatId("A10")),
                        PRICE,
                        NOW.minusSeconds(60),
                        NOW.plusSeconds(240))
                .startCheckout(NOW.minusSeconds(30), NOW.plusSeconds(300));
    }

    private static final class MutableBookingRepository implements BookingRepository {
        private Booking booking;
        private int findByIdCalls;

        private MutableBookingRepository(Booking booking) {
            this.booking = booking;
        }

        @Override
        public Optional<Booking> findById(BookingId bookingId) {
            findByIdCalls++;
            return booking.id().equals(bookingId) ? Optional.of(booking) : Optional.empty();
        }

        @Override public Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, HoldId holdId, String key) { return Optional.empty(); }
        @Override public void savePaymentIntent(Booking booking) { throw new AssertionError("not expected"); }
        @Override public void rescheduleReconciliation(Booking booking) { throw new AssertionError("not expected"); }
        @Override public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) { return List.of(); }
    }

    private static final class FixedHoldRepository implements HoldRepository {
        private final Hold hold;

        private FixedHoldRepository(Hold hold) {
            this.hold = hold;
        }

        @Override public Optional<Hold> findById(HoldId holdId) { return Optional.of(hold); }
        @Override public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) { throw new AssertionError("not expected"); }
        @Override public void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now, HoldIdempotencyKey key) { throw new AssertionError("not expected"); }
        @Override public Optional<Hold> findByIdempotencyKey(HoldIdempotencyKey key) { throw new AssertionError("not expected"); }
    }

    private static final class RacingCheckoutGateway implements CheckoutGateway {
        private final MutableBookingRepository repository;
        private final BookingStatus winnerStatus;

        private RacingCheckoutGateway(MutableBookingRepository repository, BookingStatus winnerStatus) {
            this.repository = repository;
            this.winnerStatus = winnerStatus;
        }

        @Override
        public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) {
            repository.booking = winnerStatus == BookingStatus.CONFIRMED
                    ? confirmedBooking
                    : repository.booking.fail();
            throw new CheckoutConflictException(HOLD_ID, new RuntimeException("concurrent finalization"));
        }

        @Override
        public void failBooking(ReservationCheckout reservation, Booking failedBooking) {
            repository.booking = winnerStatus == BookingStatus.FAILED
                    ? failedBooking
                    : repository.booking.confirm();
            throw new CheckoutConflictException(HOLD_ID, new RuntimeException("concurrent finalization"));
        }

        @Override public void startCheckout(ReservationCheckout reservation, Booking pendingBooking) { throw new AssertionError("not expected"); }
    }

    private static final class FixedPaymentGateway implements PaymentGateway {
        private final PaymentIntentStatus status;

        private FixedPaymentGateway(PaymentIntentStatus status) {
            this.status = status;
        }

        @Override public PaymentIntentStatus getPaymentStatus(String paymentIntentId) { return status; }
        @Override public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey) { throw new AssertionError("not expected"); }
        @Override public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) { throw new AssertionError("not expected"); }
    }
}
