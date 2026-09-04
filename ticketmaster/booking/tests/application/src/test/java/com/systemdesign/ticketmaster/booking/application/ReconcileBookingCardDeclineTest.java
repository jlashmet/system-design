package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.BookingStatus;
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

class ReconcileBookingCardDeclineTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final HoldId HOLD_ID = new HoldId("hold-123");
    private static final BookingId BOOKING_ID = new BookingId("booking-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));
    private static final Instant CHECKOUT_STARTED_AT = Instant.parse("2026-08-28T10:00:00Z");
    private static final Instant CHECKOUT_EXPIRES_AT = CHECKOUT_STARTED_AT.plus(Duration.ofMinutes(10));

    @Test
    void declinedCardKeepsSeatsProtectedAndBookingPendingBeforeCheckoutDeadline() {
        Instant now = CHECKOUT_STARTED_AT.plus(Duration.ofMinutes(2));
        Hold checkoutHold = checkoutHold();
        Booking booking = Booking.pending(
                        BOOKING_ID,
                        ReservationTestFixtures.from(checkoutHold),
                        "checkout-idempotency",
                        CHECKOUT_STARTED_AT,
                        CHECKOUT_STARTED_AT.plusSeconds(30),
                        0)
                .attachPaymentIntent("pi-123");
        TrackingBookingRepository bookings = new TrackingBookingRepository(booking);
        TrackingCheckoutGateway checkout = new TrackingCheckoutGateway();
        ReconcileBookingHandler handler = new ReconcileBookingHandler(
                ignored -> {},
                bookings,
                ReservationTestFixtures.service(new SingleHoldRepository(checkoutHold)),
                checkout,
                new DeclinedPaymentGateway(),
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofSeconds(30));

        Booking result = handler.handle(BOOKING_ID);

        assertThat(result.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(bookings.rescheduled).isEqualTo(result);
        assertThat(checkout.confirmed).isNull();
        assertThat(checkout.failed).isNull();
        assertThat(checkoutHold.checkoutExpiresAt()).isEqualTo(CHECKOUT_EXPIRES_AT);
    }

    @Test
    void declinedCardIsReleasedOnlyAfterFixedDeadlineCanCancelTheIntent() {
        Instant now = CHECKOUT_EXPIRES_AT.plusSeconds(1);
        Hold checkoutHold = checkoutHold();
        Booking booking = Booking.pending(
                        BOOKING_ID,
                        ReservationTestFixtures.from(checkoutHold),
                        "checkout-idempotency",
                        CHECKOUT_STARTED_AT,
                        CHECKOUT_STARTED_AT.plusSeconds(30),
                        0)
                .attachPaymentIntent("pi-123");
        TrackingBookingRepository bookings = new TrackingBookingRepository(booking);
        TrackingCheckoutGateway checkout = new TrackingCheckoutGateway();
        DeclinedPaymentGateway payment = new DeclinedPaymentGateway();
        ReconcileBookingHandler handler = new ReconcileBookingHandler(
                ignored -> {},
                bookings,
                ReservationTestFixtures.service(new SingleHoldRepository(checkoutHold)),
                checkout,
                payment,
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofSeconds(30));

        Booking result = handler.handle(BOOKING_ID);

        assertThat(result.status()).isEqualTo(BookingStatus.FAILED);
        assertThat(payment.cancelCalls).isOne();
        assertThat(checkout.failed).isEqualTo(result);
        assertThat(checkout.confirmed).isNull();
    }

    private static Hold checkoutHold() {
        return Hold.active(
                        HOLD_ID,
                        USER_ID,
                        EVENT_ID,
                        Set.of(new SeatId("A10")),
                        PRICE,
                        CHECKOUT_STARTED_AT.minusSeconds(30),
                        CHECKOUT_STARTED_AT.plusSeconds(270))
                .startCheckout(CHECKOUT_STARTED_AT, CHECKOUT_EXPIRES_AT);
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
            this.booking = booking;
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

    private static final class SingleHoldRepository implements HoldRepository {
        private final Hold hold;

        private SingleHoldRepository(Hold hold) {
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

    private static final class TrackingCheckoutGateway implements CheckoutGateway {
        private Booking confirmed;
        private Booking failed;

        @Override
        public void startCheckout(ReservationCheckout reservation, Booking pendingBooking) {
            throw new AssertionError("not expected");
        }

        @Override
        public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) {
            this.confirmed = confirmedBooking;
        }

        @Override
        public void failBooking(ReservationCheckout reservation, Booking failedBooking) {
            this.failed = failedBooking;
        }
    }

    private static final class DeclinedPaymentGateway implements PaymentGateway {
        private int cancelCalls;

        @Override
        public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey) {
            throw new AssertionError("not expected");
        }

        @Override
        public PaymentIntentStatus getPaymentStatus(String paymentIntentId) {
            return PaymentIntentStatus.REQUIRES_PAYMENT_METHOD;
        }

        @Override
        public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) {
            cancelCalls++;
            return PaymentIntentStatus.CANCELED;
        }
    }
}
