package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.CheckoutExpiredException;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldOwnershipException;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutService;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
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

class StartCheckoutIdempotentPaymentScopeTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final HoldId HOLD_ID = new HoldId("hold-1");
    private static final UserId OWNER = new UserId("user-owner");
    private static final UserId DRIFTED_OWNER = new UserId("user-drifted");
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    @Test
    void validatesAuthoritativeReservationBeforeCreatingMissingPaymentIntentOnIdempotentRetry() {
        ReservationCheckout bookingReservation = reservation(OWNER);
        Booking pending = pendingBooking(bookingReservation);
        ExistingBookingRepository bookings = new ExistingBookingRepository(pending);
        TrackingPaymentGateway payments = new TrackingPaymentGateway();
        StartCheckoutHandler handler = handler(
                bookings,
                payments,
                reservation(DRIFTED_OWNER));

        assertThatThrownBy(() -> handler.handle(
                        new StartCheckoutCommand(EVENT_ID, HOLD_ID, OWNER, "idem-existing")))
                .isInstanceOf(HoldOwnershipException.class);
        assertThat(payments.createCalls).isZero();
        assertThat(bookings.saveIntentCalls).isZero();
    }

    @Test
    void validatesAuthoritativeReservationBeforeReturningExistingPaymentIntentOnIdempotentRetry() {
        ReservationCheckout bookingReservation = reservation(OWNER);
        Booking pending = pendingBooking(bookingReservation).attachPaymentIntent("pi-existing");
        ExistingBookingRepository bookings = new ExistingBookingRepository(pending);
        TrackingPaymentGateway payments = new TrackingPaymentGateway();
        StartCheckoutHandler handler = handler(
                bookings,
                payments,
                reservation(DRIFTED_OWNER));

        assertThatThrownBy(() -> handler.handle(
                        new StartCheckoutCommand(EVENT_ID, HOLD_ID, OWNER, "idem-existing")))
                .isInstanceOf(HoldOwnershipException.class);
        assertThat(payments.createCalls).isZero();
        assertThat(bookings.saveIntentCalls).isZero();
    }

    @Test
    void rejectsExpiredCheckoutBeforeCreatingMissingPaymentIntentOnIdempotentRetry() {
        ReservationCheckout expiredReservation = expiredReservation();
        ExistingBookingRepository bookings = new ExistingBookingRepository(pendingBooking(expiredReservation));
        TrackingPaymentGateway payments = new TrackingPaymentGateway();
        StartCheckoutHandler handler = handler(bookings, payments, expiredReservation);

        assertThatThrownBy(() -> handler.handle(
                        new StartCheckoutCommand(EVENT_ID, HOLD_ID, OWNER, "idem-existing")))
                .isInstanceOf(CheckoutExpiredException.class)
                .hasMessage("checkout expired for hold hold-1");
        assertThat(payments.createCalls).isZero();
        assertThat(bookings.saveIntentCalls).isZero();
    }

    @Test
    void rejectsExpiredCheckoutBeforeReturningExistingPaymentIntentOnIdempotentRetry() {
        ReservationCheckout expiredReservation = expiredReservation();
        Booking pending = pendingBooking(expiredReservation).attachPaymentIntent("pi-existing");
        ExistingBookingRepository bookings = new ExistingBookingRepository(pending);
        TrackingPaymentGateway payments = new TrackingPaymentGateway();
        StartCheckoutHandler handler = handler(bookings, payments, expiredReservation);

        assertThatThrownBy(() -> handler.handle(
                        new StartCheckoutCommand(EVENT_ID, HOLD_ID, OWNER, "idem-existing")))
                .isInstanceOf(CheckoutExpiredException.class)
                .hasMessage("checkout expired for hold hold-1");
        assertThat(payments.createCalls).isZero();
        assertThat(bookings.saveIntentCalls).isZero();
    }

    private static Booking pendingBooking(ReservationCheckout bookingReservation) {
        return Booking.pending(
                new BookingId("booking-existing"),
                bookingReservation,
                "idem-existing",
                NOW.minusSeconds(10),
                NOW.plusSeconds(20),
                0);
    }

    private static StartCheckoutHandler handler(
            ExistingBookingRepository bookings,
            TrackingPaymentGateway payments,
            ReservationCheckout authoritativeReservation) {
        ReservationCheckoutService reservations = new ReservationCheckoutService() {
            @Override
            public ReservationCheckout prepareCheckout(
                    EventId eventId, HoldId holdId, UserId userId, Instant now, Instant checkoutExpiresAt) {
                throw new AssertionError("fresh checkout must not start on an idempotent retry");
            }

            @Override
            public Optional<ReservationCheckout> findById(HoldId holdId) {
                return Optional.of(authoritativeReservation);
            }
        };
        CheckoutGateway checkout = new CheckoutGateway() {
            @Override public void startCheckout(ReservationCheckout reservation, Booking pendingBooking) {
                throw new AssertionError("checkout must not restart on an idempotent retry");
            }
            @Override public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) {
                throw new AssertionError("not expected");
            }
            @Override public void failBooking(ReservationCheckout reservation, Booking failedBooking) {
                throw new AssertionError("not expected");
            }
        };
        return new StartCheckoutHandler(
                ignored -> {},
                reservations,
                bookings,
                checkout,
                payments,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                16);
    }

    private static ReservationCheckout reservation(UserId owner) {
        return new ReservationCheckout(
                HOLD_ID,
                owner,
                EVENT_ID,
                Set.of(new SeatId("A10")),
                PRICE,
                ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS,
                NOW.plusSeconds(270),
                NOW.plusSeconds(300));
    }

    private static ReservationCheckout expiredReservation() {
        return new ReservationCheckout(
                HOLD_ID,
                OWNER,
                EVENT_ID,
                Set.of(new SeatId("A10")),
                PRICE,
                ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS,
                NOW.minusSeconds(30),
                NOW.minusSeconds(1));
    }

    private static final class ExistingBookingRepository implements BookingRepository {
        private final Booking booking;
        private int saveIntentCalls;

        private ExistingBookingRepository(Booking booking) {
            this.booking = booking;
        }

        @Override public Optional<Booking> findById(BookingId bookingId) { return Optional.empty(); }

        @Override
        public Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, HoldId holdId, String key) {
            return booking.checkoutIdempotencyKey().equals(key) ? Optional.of(booking) : Optional.empty();
        }

        @Override
        public void savePaymentIntent(Booking booking) {
            saveIntentCalls++;
        }

        @Override public void rescheduleReconciliation(Booking booking) { throw new AssertionError("not expected"); }
        @Override public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) {
            return List.of();
        }
    }

    private static final class TrackingPaymentGateway implements PaymentGateway {
        private int createCalls;

        @Override
        public PaymentIntent createPaymentIntent(EventId eventId, BookingId bookingId, Price price, String key) {
            createCalls++;
            return new PaymentIntent("pi-created", PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
        }

        @Override
        public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String key) {
            createCalls++;
            return new PaymentIntent("pi-created", PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
        }

        @Override public PaymentIntentStatus getPaymentStatus(String paymentIntentId) {
            throw new AssertionError("not expected");
        }

        @Override public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) {
            throw new AssertionError("not expected");
        }
    }
}
