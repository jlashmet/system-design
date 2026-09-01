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
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.HoldNotFoundException;
import com.systemdesign.ticketmaster.booking.domain.HoldOwnershipException;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import com.systemdesign.ticketmaster.booking.domain.PaymentProviderUnavailableException;
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

class StartCheckoutHandlerTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final HoldId HOLD_ID = new HoldId("hold-1");
    private static final UserId OWNER = new UserId("user-owner");
    private static final UserId OTHER_USER = new UserId("user-other");
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    private TrackingBookingRepository bookingRepository;
    private TrackingHoldRepository holdRepository;
    private TrackingCheckoutGateway checkoutGateway;
    private TrackingPaymentGateway paymentGateway;
    private StartCheckoutHandler handler;
    private StartCheckoutResult result;
    private Throwable thrown;

    @Test
    void rejectsWrongRegionBeforeReadingCheckoutState() {
        givenWrongBookingRegion();
        whenCheckoutStartsAs(OWNER, "idem-1");
        thenExpectWrongRegionBeforeCheckoutStateRead();
    }

    @Test
    void missingHoldIsResourceMissBeforePayment() {
        givenMissingHold();
        whenCheckoutStartsAs(OWNER, "idem-missing");
        thenExpectHoldNotFoundBeforeCheckoutOrPayment();
    }

    @Test
    void rejectsDifferentUserBeforeStartingFreshCheckout() {
        givenActiveHoldOwnedBy(OWNER);
        whenCheckoutStartsAs(OTHER_USER, "idem-2");
        thenExpectHoldOwnershipRejectedBeforeCheckoutOrPayment();
    }

    @Test
    void rejectsDifferentUserOnIdempotentRetryBeforeProviderAccess() {
        givenExistingCheckoutOwnedBy(OWNER);
        whenCheckoutStartsAs(OTHER_USER, "idem-existing");
        thenExpectHoldOwnershipRejectedBeforeProviderAccess();
    }

    @Test
    void returnsIdempotentCheckoutWithoutProviderAccess() {
        givenExistingCheckoutOwnedBy(OWNER);
        whenCheckoutStartsAs(OWNER, "idem-existing");
        thenExpectExistingCheckoutReturnedWithoutProviderAccess();
    }

    @Test
    void exposesPaymentProviderFailureAfterCheckoutHasStarted() {
        givenActiveHoldWithUnavailablePaymentProvider();
        whenCheckoutStartsAs(OWNER, "idem-provider-down");
        thenExpectRetryableProviderFailureAfterCheckoutStarted("payment intent creation");
    }

    private void givenWrongBookingRegion() {
        bookingRepository = new TrackingBookingRepository();
        holdRepository = new TrackingHoldRepository(null);
        checkoutGateway = new TrackingCheckoutGateway();
        paymentGateway = new TrackingPaymentGateway();
        EventWriteAuthority wrongRegion = eventId -> {
            throw new WrongBookingRegionException(eventId, "us-east-1", "us-west-2");
        };
        handler = handler(wrongRegion);
        resetResult();
    }

    private void givenMissingHold() {
        bookingRepository = new TrackingBookingRepository();
        holdRepository = new TrackingHoldRepository(null);
        checkoutGateway = new TrackingCheckoutGateway();
        paymentGateway = new TrackingPaymentGateway();
        handler = handler(ignored -> {});
        resetResult();
    }

    private void givenActiveHoldOwnedBy(UserId owner) {
        bookingRepository = new TrackingBookingRepository();
        holdRepository = new TrackingHoldRepository(activeHold(owner));
        checkoutGateway = new TrackingCheckoutGateway();
        paymentGateway = new TrackingPaymentGateway();
        handler = handler(ignored -> {});
        resetResult();
    }

    private void givenActiveHoldWithUnavailablePaymentProvider() {
        bookingRepository = new TrackingBookingRepository();
        holdRepository = new TrackingHoldRepository(activeHold(OWNER));
        checkoutGateway = new TrackingCheckoutGateway();
        paymentGateway = new TrackingPaymentGateway(true);
        handler = handler(ignored -> {});
        resetResult();
    }

    private void givenExistingCheckoutOwnedBy(UserId owner) {
        Hold checkoutHold = activeHold(owner).startCheckout(NOW.minusSeconds(10), NOW.plusSeconds(300));
        Booking booking = Booking.pending(
                        new BookingId("booking-existing"),
                        ReservationTestFixtures.from(checkoutHold),
                        "idem-existing",
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(20),
                        0)
                .attachPaymentIntent("pi-existing");
        bookingRepository = new TrackingBookingRepository(booking);
        holdRepository = new TrackingHoldRepository(null);
        checkoutGateway = new TrackingCheckoutGateway();
        paymentGateway = new TrackingPaymentGateway();
        handler = handler(ignored -> {});
        resetResult();
    }

    private void whenCheckoutStartsAs(UserId userId, String idempotencyKey) {
        try {
            result = handler.handle(new StartCheckoutCommand(EVENT_ID, HOLD_ID, userId, idempotencyKey));
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectWrongRegionBeforeCheckoutStateRead() {
        assertThat(thrown).isInstanceOf(WrongBookingRegionException.class);
        assertThat(result).isNull();
        assertThat(bookingRepository.idempotencyReads).isZero();
        assertThat(holdRepository.findByIdReads).isZero();
    }

    private void thenExpectHoldNotFoundBeforeCheckoutOrPayment() {
        assertThat(thrown).isInstanceOf(HoldNotFoundException.class);
        assertThat(result).isNull();
        assertThat(holdRepository.findByIdReads).isOne();
        assertThat(checkoutGateway.startCalls).isZero();
        assertThat(paymentGateway.createCalls).isZero();
        assertThat(paymentGateway.statusCalls).isZero();
    }

    private void thenExpectHoldOwnershipRejectedBeforeCheckoutOrPayment() {
        assertThat(thrown).isInstanceOf(HoldOwnershipException.class);
        assertThat(result).isNull();
        assertThat(holdRepository.findByIdReads).isOne();
        assertThat(checkoutGateway.startCalls).isZero();
        assertThat(paymentGateway.createCalls).isZero();
        assertThat(paymentGateway.statusCalls).isZero();
    }

    private void thenExpectHoldOwnershipRejectedBeforeProviderAccess() {
        assertThat(thrown).isInstanceOf(HoldOwnershipException.class);
        assertThat(result).isNull();
        assertThat(bookingRepository.idempotencyReads).isOne();
        assertThat(holdRepository.findByIdReads).isZero();
        assertThat(paymentGateway.createCalls).isZero();
        assertThat(paymentGateway.statusCalls).isZero();
    }

    private void thenExpectExistingCheckoutReturnedWithoutProviderAccess() {
        assertThat(thrown).isNull();
        assertThat(result).isNotNull();
        assertThat(result.booking().id()).isEqualTo(new BookingId("booking-existing"));
        assertThat(result.paymentIntentId()).isEqualTo("pi-existing");
        assertThat(bookingRepository.idempotencyReads).isOne();
        assertThat(holdRepository.findByIdReads).isZero();
        assertThat(checkoutGateway.startCalls).isZero();
        assertThat(paymentGateway.createCalls).isZero();
        assertThat(paymentGateway.statusCalls).isZero();
    }

    private void thenExpectRetryableProviderFailureAfterCheckoutStarted(String expectedOperation) {
        assertThat(thrown).isInstanceOf(PaymentProviderUnavailableException.class);
        assertThat(((PaymentProviderUnavailableException) thrown).operation()).isEqualTo(expectedOperation);
        assertThat(result).isNull();
        assertThat(checkoutGateway.startCalls).isOne();
        assertThat(paymentGateway.createCalls).isOne();
        assertThat(paymentGateway.createEventId).isEqualTo(EVENT_ID);
        assertThat(paymentGateway.statusCalls).isZero();
    }

    private void resetResult() {
        result = null;
        thrown = null;
    }

    private StartCheckoutHandler handler(EventWriteAuthority authority) {
        return new StartCheckoutHandler(
                authority,
                ReservationTestFixtures.service(holdRepository),
                bookingRepository,
                checkoutGateway,
                paymentGateway,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                16);
    }

    private static Hold activeHold(UserId owner) {
        return Hold.active(
                HOLD_ID,
                owner,
                EVENT_ID,
                Set.of(new SeatId("A10")),
                PRICE,
                NOW.minusSeconds(30),
                NOW.plusSeconds(270));
    }

    private static final class TrackingBookingRepository implements BookingRepository {
        private final Booking idempotentBooking;
        private int idempotencyReads;

        private TrackingBookingRepository() {
            this(null);
        }

        private TrackingBookingRepository(Booking idempotentBooking) {
            this.idempotentBooking = idempotentBooking;
        }

        @Override public Optional<Booking> findById(BookingId bookingId) { return Optional.empty(); }

        @Override
        public Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, HoldId holdId, String key) {
            idempotencyReads++;
            if (idempotentBooking == null || !idempotentBooking.checkoutIdempotencyKey().equals(key)) {
                return Optional.empty();
            }
            return Optional.of(idempotentBooking);
        }

        @Override public void savePaymentIntent(Booking booking) { throw new AssertionError("not expected"); }
        @Override public void rescheduleReconciliation(Booking booking) { throw new AssertionError("not expected"); }
        @Override public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) { return List.of(); }
    }

    private static final class TrackingHoldRepository implements HoldRepository {
        private final Hold hold;
        private int findByIdReads;

        private TrackingHoldRepository(Hold hold) {
            this.hold = hold;
        }

        @Override public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) { throw new AssertionError("not expected"); }
        @Override public void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now, HoldIdempotencyKey key) {
            throw new AssertionError("not expected");
        }

        @Override
        public Optional<Hold> findById(HoldId holdId) {
            findByIdReads++;
            return hold == null ? Optional.empty() : Optional.of(hold);
        }

        @Override public Optional<Hold> findByIdempotencyKey(HoldIdempotencyKey key) { throw new AssertionError("not expected"); }
    }

    private static final class TrackingCheckoutGateway implements CheckoutGateway {
        private int startCalls;

        @Override
        public void startCheckout(ReservationCheckout reservation, Booking pendingBooking) {
            startCalls++;
        }

        @Override public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) { throw new AssertionError("not expected"); }
        @Override public void failBooking(ReservationCheckout reservation, Booking failedBooking) { throw new AssertionError("not expected"); }
    }

    private static final class TrackingPaymentGateway implements PaymentGateway {
        private final boolean failCreate;
        private int createCalls;
        private int statusCalls;
        private EventId createEventId;

        private TrackingPaymentGateway() {
            this(false);
        }

        private TrackingPaymentGateway(boolean failCreate) {
            this.failCreate = failCreate;
        }

        @Override
        public PaymentIntent createPaymentIntent(
                EventId eventId, BookingId bookingId, Price price, String key) {
            createEventId = eventId;
            return createPaymentIntent(bookingId, price, key);
        }

        @Override
        public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String key) {
            createCalls++;
            if (failCreate) {
                throw new PaymentProviderUnavailableException(
                        "payment intent creation", new RuntimeException("payment provider unavailable"));
            }
            return new PaymentIntent("pi-created", PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
        }

        @Override
        public PaymentIntentStatus getPaymentStatus(String paymentIntentId) {
            statusCalls++;
            return PaymentIntentStatus.PROCESSING;
        }

        @Override public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) { throw new AssertionError("not expected"); }
    }
}
