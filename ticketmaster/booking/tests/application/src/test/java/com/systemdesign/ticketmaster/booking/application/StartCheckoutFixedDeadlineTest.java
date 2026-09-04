package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
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

class StartCheckoutFixedDeadlineTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final HoldId HOLD_ID = new HoldId("hold-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final SeatId SEAT_ID = new SeatId("A10");
    private static final Instant CHECKOUT_STARTED_AT = Instant.parse("2026-08-28T10:00:00Z");
    private static final Instant CHECKOUT_EXPIRES_AT = CHECKOUT_STARTED_AT.plus(Duration.ofMinutes(10));
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    @Test
    void idempotentCheckoutRetryReturnsOriginalDeadlineInsteadOfStartingANewWindow() {
        Hold checkoutHold = Hold.checkout(
                HOLD_ID,
                USER_ID,
                EVENT_ID,
                Set.of(SEAT_ID),
                PRICE,
                CHECKOUT_STARTED_AT,
                CHECKOUT_EXPIRES_AT);
        Booking existingBooking = Booking.pending(
                        new BookingId("booking-123"),
                        ReservationTestFixtures.from(checkoutHold),
                        "idem-123",
                        CHECKOUT_STARTED_AT,
                        CHECKOUT_STARTED_AT.plusSeconds(30),
                        0)
                .attachPaymentIntent("pi-123");
        ExistingBookingRepository bookings = new ExistingBookingRepository(existingBooking);
        NeverPaymentGateway payment = new NeverPaymentGateway();
        StartCheckoutHandler handler = new StartCheckoutHandler(
                ignored -> {},
                ReservationTestFixtures.service(new SingleHoldRepository(checkoutHold)),
                bookings,
                new NeverCheckoutGateway(),
                payment,
                Clock.fixed(CHECKOUT_STARTED_AT.plus(Duration.ofMinutes(4)), ZoneOffset.UTC),
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                16);

        StartCheckoutResult result = handler.handle(
                new StartCheckoutCommand(EVENT_ID, USER_ID, List.of(SEAT_ID), "idem-123", null));

        assertThat(result.booking()).isEqualTo(existingBooking);
        assertThat(result.paymentIntentId()).isEqualTo("pi-123");
        assertThat(result.checkoutExpiresAt()).isEqualTo(CHECKOUT_EXPIRES_AT);
        assertThat(payment.calls).isZero();
    }

    private static final class ExistingBookingRepository implements BookingRepository {
        private final Booking booking;

        private ExistingBookingRepository(Booking booking) {
            this.booking = booking;
        }

        @Override
        public Optional<Booking> findById(BookingId bookingId) {
            return booking.id().equals(bookingId) ? Optional.of(booking) : Optional.empty();
        }

        @Override
        public Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, UserId userId, String key) {
            return booking.eventId().equals(eventId)
                    && booking.userId().equals(userId)
                    && booking.checkoutIdempotencyKey().equals(key)
                    ? Optional.of(booking)
                    : Optional.empty();
        }

        @Override public void savePaymentIntent(Booking booking) { throw new AssertionError("not expected"); }
        @Override public void rescheduleReconciliation(Booking booking) { throw new AssertionError("not expected"); }
        @Override public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) { return List.of(); }
    }

    private static final class SingleHoldRepository implements HoldRepository {
        private final Hold hold;

        private SingleHoldRepository(Hold hold) {
            this.hold = hold;
        }

        @Override public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) { throw new AssertionError("not expected"); }
        @Override public Optional<Hold> findById(HoldId holdId) { return hold.id().equals(holdId) ? Optional.of(hold) : Optional.empty(); }
    }

    private static final class NeverCheckoutGateway implements CheckoutGateway {
        @Override public void startCheckout(PreparedCheckout preparedCheckout, Booking pendingBooking) { throw new AssertionError("not expected"); }
        @Override public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) { throw new AssertionError("not expected"); }
        @Override public void failBooking(ReservationCheckout reservation, Booking failedBooking) { throw new AssertionError("not expected"); }
    }

    private static final class NeverPaymentGateway implements PaymentGateway {
        private int calls;

        @Override
        public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey) {
            calls++;
            throw new AssertionError("not expected");
        }

        @Override
        public PaymentIntentStatus getPaymentStatus(String paymentIntentId) {
            calls++;
            throw new AssertionError("not expected");
        }

        @Override
        public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) {
            calls++;
            throw new AssertionError("not expected");
        }
    }
}
