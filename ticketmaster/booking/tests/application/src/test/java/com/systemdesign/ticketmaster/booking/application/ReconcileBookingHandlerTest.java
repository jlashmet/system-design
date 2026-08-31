package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import com.systemdesign.ticketmaster.booking.domain.Price;
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

class ReconcileBookingHandlerTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final HoldId HOLD_ID = new HoldId("hold-123");
    private static final BookingId BOOKING_ID = new BookingId("booking-123");
    private static final Instant CREATED_AT = Instant.parse("2026-08-27T22:00:00Z");
    private static final Instant CHECKOUT_STARTED_AT = CREATED_AT.plusSeconds(60);
    private static final Instant CHECKOUT_DEADLINE = CREATED_AT.plusSeconds(120);
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));
    private static final EventWriteAuthority LOCAL_OWNER = ignored -> {};

    @Test
    void cancelsExpiredPendingPaymentBeforeReleasingSeats() {
        Scenario scenario = scenario(LOCAL_OWNER, CHECKOUT_DEADLINE.plusSeconds(1), PaymentIntentStatus.PROCESSING,
                PaymentIntentStatus.CANCELED);
        Booking result = scenario.handler.handle(BOOKING_ID);
        assertThat(result.status().name()).isEqualTo("FAILED");
        assertThat(scenario.paymentGateway.cancelCalls).isEqualTo(1);
        assertThat(scenario.checkoutGateway.failedBooking).isEqualTo(result);
        assertThat(scenario.checkoutGateway.failedHold.status().name()).isEqualTo("FAILED");
        assertThat(scenario.checkoutGateway.confirmedBooking).isNull();
    }

    @Test
    void booksWhenCancellationRacesWithPaymentSuccess() {
        Scenario scenario = scenario(LOCAL_OWNER, CHECKOUT_DEADLINE.plusSeconds(1), PaymentIntentStatus.PROCESSING,
                PaymentIntentStatus.SUCCEEDED);
        Booking result = scenario.handler.handle(BOOKING_ID);
        assertThat(result.status().name()).isEqualTo("CONFIRMED");
        assertThat(scenario.paymentGateway.cancelCalls).isEqualTo(1);
        assertThat(scenario.checkoutGateway.confirmedBooking).isEqualTo(result);
        assertThat(scenario.checkoutGateway.confirmedHold.status().name()).isEqualTo("CONVERTED");
        assertThat(scenario.checkoutGateway.failedBooking).isNull();
    }

    @Test
    void keepsCheckoutProtectedBeforeDeadline() {
        Scenario scenario = scenario(LOCAL_OWNER, CHECKOUT_DEADLINE.minusSeconds(1), PaymentIntentStatus.PROCESSING,
                PaymentIntentStatus.CANCELED);
        Booking result = scenario.handler.handle(BOOKING_ID);
        assertThat(result.status().name()).isEqualTo("PENDING_PAYMENT");
        assertThat(scenario.paymentGateway.cancelCalls).isZero();
        assertThat(scenario.bookingRepository.rescheduled).isEqualTo(result);
        assertThat(scenario.checkoutGateway.confirmedBooking).isNull();
        assertThat(scenario.checkoutGateway.failedBooking).isNull();
    }

    @Test
    void rejectsReconciliationOutsideOwningRegionBeforePaymentOrHoldAccess() {
        EventWriteAuthority wrongRegion = eventId -> {
            throw new WrongBookingRegionException(eventId, "us-east-1", "us-west-2");
        };
        Scenario scenario = scenario(wrongRegion, CHECKOUT_DEADLINE.plusSeconds(1), PaymentIntentStatus.SUCCEEDED,
                PaymentIntentStatus.SUCCEEDED);
        assertThatThrownBy(() -> scenario.handler.handle(BOOKING_ID)).isInstanceOf(WrongBookingRegionException.class);
        assertThat(scenario.paymentGateway.statusCalls).isZero();
        assertThat(scenario.paymentGateway.cancelCalls).isZero();
        assertThat(scenario.holdRepository.findCalls).isZero();
        assertThat(scenario.checkoutGateway.confirmedBooking).isNull();
        assertThat(scenario.checkoutGateway.failedBooking).isNull();
    }

    private static Scenario scenario(EventWriteAuthority authority, Instant now,
                                     PaymentIntentStatus paymentStatus, PaymentIntentStatus cancelStatus) {
        Hold active = Hold.active(HOLD_ID, new UserId("user-456"), EVENT_ID, Set.of(new SeatId("A10")), PRICE,
                CREATED_AT, CREATED_AT.plusSeconds(300));
        Hold checkout = active.startCheckout(CHECKOUT_STARTED_AT, CHECKOUT_DEADLINE);
        Booking booking = Booking.pending(BOOKING_ID, checkout, "checkout-idempotency", CHECKOUT_STARTED_AT,
                        CHECKOUT_STARTED_AT.plusSeconds(30), 0).attachPaymentIntent("pi-123");
        FakeBookingRepository bookings = new FakeBookingRepository(booking);
        FakeHoldRepository holds = new FakeHoldRepository(checkout);
        FakeCheckoutGateway checkoutGateway = new FakeCheckoutGateway();
        FakePaymentGateway paymentGateway = new FakePaymentGateway(paymentStatus, cancelStatus);
        ReconcileBookingHandler handler = new ReconcileBookingHandler(authority, bookings, holds, checkoutGateway,
                paymentGateway, Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(30));
        return new Scenario(handler, bookings, holds, checkoutGateway, paymentGateway);
    }

    private record Scenario(ReconcileBookingHandler handler, FakeBookingRepository bookingRepository,
                            FakeHoldRepository holdRepository, FakeCheckoutGateway checkoutGateway,
                            FakePaymentGateway paymentGateway) {}

    private static final class FakeBookingRepository implements BookingRepository {
        private Booking booking;
        private Booking rescheduled;
        private FakeBookingRepository(Booking booking) { this.booking = booking; }
        @Override public Optional<Booking> findById(BookingId bookingId) {
            return booking.id().equals(bookingId) ? Optional.of(booking) : Optional.empty();
        }
        @Override public Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, HoldId holdId, String key) {
            return Optional.empty();
        }
        @Override public void savePaymentIntent(Booking booking) { this.booking = booking; }
        @Override public void rescheduleReconciliation(Booking booking) { this.booking = booking; this.rescheduled = booking; }
        @Override public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) { return List.of(); }
    }

    private static final class FakeHoldRepository implements HoldRepository {
        private final Hold hold;
        private int findCalls;
        private FakeHoldRepository(Hold hold) { this.hold = hold; }
        @Override public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) { throw new UnsupportedOperationException(); }
        @Override public void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now, HoldIdempotencyKey key) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<Hold> findById(HoldId holdId) {
            findCalls++;
            return hold.id().equals(holdId) ? Optional.of(hold) : Optional.empty();
        }
        @Override public Optional<Hold> findByIdempotencyKey(HoldIdempotencyKey key) { throw new UnsupportedOperationException(); }
    }

    private static final class FakeCheckoutGateway implements CheckoutGateway {
        private Hold confirmedHold;
        private Booking confirmedBooking;
        private Hold failedHold;
        private Booking failedBooking;
        @Override public void startCheckout(Hold checkoutHold, Booking pendingBooking) { throw new UnsupportedOperationException(); }
        @Override public void finalizeBooking(Hold convertedHold, Booking confirmedBooking) {
            this.confirmedHold = convertedHold; this.confirmedBooking = confirmedBooking;
        }
        @Override public void failBooking(Hold failedHold, Booking failedBooking) {
            this.failedHold = failedHold; this.failedBooking = failedBooking;
        }
    }

    private static final class FakePaymentGateway implements PaymentGateway {
        private final PaymentIntentStatus paymentStatus;
        private final PaymentIntentStatus cancelStatus;
        private int statusCalls;
        private int cancelCalls;
        private FakePaymentGateway(PaymentIntentStatus paymentStatus, PaymentIntentStatus cancelStatus) {
            this.paymentStatus = paymentStatus; this.cancelStatus = cancelStatus;
        }
        @Override public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String key) {
            return new PaymentIntent("pi-123", paymentStatus);
        }
        @Override public PaymentIntentStatus getPaymentStatus(String paymentIntentId) { statusCalls++; return paymentStatus; }
        @Override public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) { cancelCalls++; return cancelStatus; }
    }
}
