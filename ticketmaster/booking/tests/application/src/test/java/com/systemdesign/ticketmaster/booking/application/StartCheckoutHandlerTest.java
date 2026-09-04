package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.CheckoutIdempotencyConflictException;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import com.systemdesign.ticketmaster.booking.domain.PaymentProviderUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.PreparedCheckout;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutService;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StartCheckoutHandlerTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final SeatId A10 = new SeatId("A10");
    private static final SeatId A11 = new SeatId("A11");
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final Duration CHECKOUT_DURATION = Duration.ofMinutes(10);
    private static final Price A10_PRICE = price("100.00");
    private static final Price A11_PRICE = price("125.00");
    private static final Price TOTAL = price("225.00");

    @Test
    void nextClaimsSelectedSeatsAndStartsOneFixedCheckoutWindow() {
        Fixture fixture = fixture();

        StartCheckoutResult result = fixture.handler.handle(command(List.of(A10, A11), "idem-1"));

        assertThat(fixture.reservations.prepareCalls).isOne();
        assertThat(fixture.reservations.preparedSeatIds).containsExactlyInAnyOrder(A10, A11);
        assertThat(fixture.reservations.preparedDeadline).isEqualTo(NOW.plus(CHECKOUT_DURATION));
        assertThat(fixture.checkout.started).isNotNull();
        assertThat(fixture.checkout.started.reservation().status())
                .isEqualTo(ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS);
        assertThat(fixture.checkout.started.reservation().checkoutExpiresAt())
                .isEqualTo(NOW.plus(CHECKOUT_DURATION));
        assertThat(result.checkoutExpiresAt()).isEqualTo(NOW.plus(CHECKOUT_DURATION));
        assertThat(result.paymentIntentId()).isEqualTo("pi-created");
        assertThat(fixture.payments.createCalls).isOne();
    }

    @Test
    void idempotentRetryReturnsOriginalCheckoutWithoutMovingDeadlineOrCallingProvider() {
        Fixture fixture = fixture();
        StartCheckoutResult first = fixture.handler.handle(command(List.of(A10, A11), "idem-1"));
        fixture.resetInteractionCounts();

        StartCheckoutResult retry = fixture.handler.handle(command(List.of(A10, A11), "idem-1"));

        assertThat(retry.booking().id()).isEqualTo(first.booking().id());
        assertThat(retry.paymentIntentId()).isEqualTo(first.paymentIntentId());
        assertThat(retry.checkoutExpiresAt()).isEqualTo(first.checkoutExpiresAt());
        assertThat(fixture.reservations.prepareCalls).isZero();
        assertThat(fixture.checkout.startCalls).isZero();
        assertThat(fixture.payments.createCalls).isZero();
    }

    @Test
    void reusingIdempotencyKeyForDifferentSeatSelectionIsConflict() {
        Fixture fixture = fixture();
        fixture.handler.handle(command(List.of(A10, A11), "idem-1"));

        Throwable thrown = capture(() -> fixture.handler.handle(command(List.of(A10), "idem-1")));

        assertThat(thrown).isInstanceOf(CheckoutIdempotencyConflictException.class);
    }

    @Test
    void sameClientKeyIsScopedByUser() {
        Fixture fixture = fixture();
        StartCheckoutResult first = fixture.handler.handle(command(List.of(A10), "same-key"));
        UserId other = new UserId("user-other");

        StartCheckoutResult second = fixture.handler.handle(
                new StartCheckoutCommand(EVENT_ID, other, List.of(A11), "same-key", null));

        assertThat(second.booking().id()).isNotEqualTo(first.booking().id());
        assertThat(second.booking().userId()).isEqualTo(other);
        assertThat(fixture.checkout.startCalls).isEqualTo(2);
    }

    @Test
    void rejectsWrongRegionBeforeIdempotencyOrReservationPreparation() {
        Fixture fixture = fixture(eventId -> {
            throw new WrongBookingRegionException(eventId, "us-east-1", "us-west-2");
        }, false);

        Throwable thrown = capture(() -> fixture.handler.handle(command(List.of(A10), "idem-1")));

        assertThat(thrown).isInstanceOf(WrongBookingRegionException.class);
        assertThat(fixture.bookings.idempotencyReads).isZero();
        assertThat(fixture.reservations.prepareCalls).isZero();
        assertThat(fixture.checkout.startCalls).isZero();
        assertThat(fixture.payments.createCalls).isZero();
    }

    @Test
    void paymentProviderFailureDoesNotUndoStartedCheckout() {
        Fixture fixture = fixture(ignored -> {}, true);

        Throwable thrown = capture(() -> fixture.handler.handle(command(List.of(A10), "idem-provider-down")));

        assertThat(thrown).isInstanceOf(PaymentProviderUnavailableException.class);
        assertThat(fixture.checkout.startCalls).isOne();
        assertThat(fixture.bookings.idempotencyReads).isOne();
        assertThat(fixture.payments.createCalls).isOne();
    }

    private static Fixture fixture() {
        return fixture(ignored -> {}, false);
    }

    private static Fixture fixture(EventWriteAuthority authority, boolean failPaymentCreate) {
        FakeReservationCheckoutService reservations = new FakeReservationCheckoutService();
        FakeBookingRepository bookings = new FakeBookingRepository();
        FakeCheckoutGateway checkout = new FakeCheckoutGateway(bookings, reservations);
        FakePaymentGateway payments = new FakePaymentGateway(failPaymentCreate);
        StartCheckoutHandler handler = new StartCheckoutHandler(
                authority,
                reservations,
                bookings,
                checkout,
                payments,
                Clock.fixed(NOW, ZoneOffset.UTC),
                CHECKOUT_DURATION,
                Duration.ofSeconds(30),
                16);
        return new Fixture(handler, reservations, bookings, checkout, payments);
    }

    private static StartCheckoutCommand command(List<SeatId> seats, String key) {
        return new StartCheckoutCommand(EVENT_ID, USER_ID, seats, key, null);
    }

    private static Throwable capture(Operation operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable thrown) {
            return thrown;
        }
    }

    private static Price price(String amount) {
        return new Price(new BigDecimal(amount), Currency.getInstance("USD"));
    }

    private record Fixture(
            StartCheckoutHandler handler,
            FakeReservationCheckoutService reservations,
            FakeBookingRepository bookings,
            FakeCheckoutGateway checkout,
            FakePaymentGateway payments) {
        void resetInteractionCounts() {
            reservations.prepareCalls = 0;
            bookings.idempotencyReads = 0;
            checkout.startCalls = 0;
            payments.createCalls = 0;
        }
    }

    @FunctionalInterface
    private interface Operation {
        void run();
    }

    private static final class FakeReservationCheckoutService implements ReservationCheckoutService {
        private final java.util.Map<HoldId, ReservationCheckout> byId = new java.util.HashMap<>();
        private int sequence;
        private int prepareCalls;
        private Set<SeatId> preparedSeatIds;
        private Instant preparedDeadline;

        @Override
        public PreparedCheckout prepareCheckout(EventId eventId, UserId userId, Set<SeatId> seatIds,
                                                String admissionToken, Instant now, Instant checkoutExpiresAt) {
            prepareCalls++;
            preparedSeatIds = Set.copyOf(seatIds);
            preparedDeadline = checkoutExpiresAt;
            HoldId id = new HoldId("checkout-" + (++sequence));
            Price total = seatIds.size() == 2 ? TOTAL : seatIds.contains(A10) ? A10_PRICE : A11_PRICE;
            ReservationCheckout reservation = new ReservationCheckout(
                    id, userId, eventId, seatIds, total,
                    ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS, checkoutExpiresAt);
            java.util.Map<SeatId, Price> prices = new java.util.HashMap<>();
            for (SeatId seat : seatIds) prices.put(seat, seat.equals(A10) ? A10_PRICE : A11_PRICE);
            byId.put(id, reservation);
            return new PreparedCheckout(reservation, prices);
        }

        @Override
        public Optional<ReservationCheckout> findById(HoldId holdId) {
            return Optional.ofNullable(byId.get(holdId));
        }
    }

    private static final class FakeBookingRepository implements BookingRepository {
        private final java.util.Map<BookingId, Booking> byId = new java.util.HashMap<>();
        private final java.util.Map<String, Booking> byScope = new java.util.HashMap<>();
        private int idempotencyReads;

        @Override
        public Optional<Booking> findById(BookingId bookingId) {
            return Optional.ofNullable(byId.get(bookingId));
        }

        @Override
        public Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, UserId userId, String key) {
            idempotencyReads++;
            return Optional.ofNullable(byScope.get(scope(eventId, userId, key)));
        }

        void recordStarted(Booking booking) {
            byId.put(booking.id(), booking);
            byScope.put(scope(booking.eventId(), booking.userId(), booking.checkoutIdempotencyKey()), booking);
        }

        @Override
        public void savePaymentIntent(Booking booking) {
            recordStarted(booking);
        }

        @Override public void rescheduleReconciliation(Booking booking) { recordStarted(booking); }
        @Override public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) { return List.of(); }

        private static String scope(EventId eventId, UserId userId, String key) {
            return eventId.value() + "|" + userId.value() + "|" + key;
        }
    }

    private static final class FakeCheckoutGateway implements CheckoutGateway {
        private final FakeBookingRepository bookings;
        private final FakeReservationCheckoutService reservations;
        private PreparedCheckout started;
        private int startCalls;

        private FakeCheckoutGateway(FakeBookingRepository bookings, FakeReservationCheckoutService reservations) {
            this.bookings = bookings;
            this.reservations = reservations;
        }

        @Override
        public void startCheckout(PreparedCheckout preparedCheckout, Booking pendingBooking) {
            startCalls++;
            started = preparedCheckout;
            reservations.byId.put(preparedCheckout.reservation().id(), preparedCheckout.reservation());
            bookings.recordStarted(pendingBooking);
        }

        @Override public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) { throw new AssertionError("not expected"); }
        @Override public void failBooking(ReservationCheckout reservation, Booking failedBooking) { throw new AssertionError("not expected"); }
    }

    private static final class FakePaymentGateway implements PaymentGateway {
        private final boolean failCreate;
        private int createCalls;

        private FakePaymentGateway(boolean failCreate) {
            this.failCreate = failCreate;
        }

        @Override
        public PaymentIntent createPaymentIntent(EventId eventId, BookingId bookingId, Price price, String key) {
            createCalls++;
            if (failCreate) {
                throw new PaymentProviderUnavailableException(
                        "payment intent creation", new RuntimeException("payment provider unavailable"));
            }
            return new PaymentIntent("pi-created", PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
        }

        @Override public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String key) {
            return createPaymentIntent(EVENT_ID, bookingId, price, key);
        }
        @Override public PaymentIntentStatus getPaymentStatus(String paymentIntentId) { throw new AssertionError("not expected"); }
        @Override public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) { throw new AssertionError("not expected"); }
    }
}
