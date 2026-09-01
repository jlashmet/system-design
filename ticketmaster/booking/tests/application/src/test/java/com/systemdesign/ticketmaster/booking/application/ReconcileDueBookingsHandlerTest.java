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
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReconcileDueBookingsHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-29T01:30:00Z");
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));
    private static final BookingId BOOKING_ONE = new BookingId("booking-1");
    private static final BookingId BOOKING_TWO = new BookingId("booking-2");

    private TrackingBookingRepository repository;
    private ReconcileDueBookingsHandler handler;
    private ReconciliationBatchResult result;

    @Test
    void shardReadFailureDoesNotSkipLaterShards() {
        givenShardZeroFailsAndShardOneHasBooking();
        whenDueBookingsReconciled();
        thenExpectLaterShardProcessed();
    }

    @Test
    void bookingFailureDoesNotSkipLaterBooking() {
        givenFirstBookingCannotBeReloaded();
        whenDueBookingsReconciled();
        thenExpectLaterBookingProcessed();
    }

    private void givenShardZeroFailsAndShardOneHasBooking() {
        Booking booking = confirmedBooking(BOOKING_TWO);
        repository = new TrackingBookingRepository(0, List.of(), List.of(booking), List.of(), Set.of(BOOKING_TWO));
        handler = handler(repository, 3);
        result = null;
    }

    private void givenFirstBookingCannotBeReloaded() {
        Booking missing = confirmedBooking(BOOKING_ONE);
        Booking available = confirmedBooking(BOOKING_TWO);
        repository = new TrackingBookingRepository(-1, List.of(missing, available), List.of(), List.of(), Set.of(BOOKING_TWO));
        handler = handler(repository, 1);
        result = null;
    }

    private void whenDueBookingsReconciled() {
        result = handler.handle();
    }

    private void thenExpectLaterShardProcessed() {
        assertThat(result.processed()).isOne();
        assertThat(result.errors()).isEmpty();
        assertThat(result.failedShards()).containsExactly(0);
        assertThat(repository.queriedShards).containsExactly(0, 1, 2);
        assertThat(repository.findByIdCalls).isOne();
    }

    private void thenExpectLaterBookingProcessed() {
        assertThat(result.processed()).isEqualTo(2);
        assertThat(result.errors()).containsExactly(BOOKING_ONE);
        assertThat(result.failedShards()).isEmpty();
        assertThat(repository.findByIdCalls).isEqualTo(2);
    }

    private static ReconcileDueBookingsHandler handler(TrackingBookingRepository repository, int shards) {
        ReconcileBookingHandler reconcile = new ReconcileBookingHandler(
                ignored -> {},
                repository,
                ReservationTestFixtures.service(new UnusedHoldRepository()),
                new UnusedCheckoutGateway(),
                new UnusedPaymentGateway(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
        return new ReconcileDueBookingsHandler(
                repository,
                reconcile,
                Clock.fixed(NOW, ZoneOffset.UTC),
                shards,
                25);
    }

    private static Booking confirmedBooking(BookingId bookingId) {
        return new Booking(
                bookingId,
                new UserId("user-123"),
                EVENT_ID,
                new HoldId("hold-" + bookingId.value()),
                BookingStatus.CONFIRMED,
                PRICE,
                "checkout-" + bookingId.value(),
                "pi-" + bookingId.value(),
                null,
                null,
                NOW.minusSeconds(60));
    }

    private static final class TrackingBookingRepository implements BookingRepository {
        private final int failingShard;
        private final List<List<Booking>> bookingsByShard;
        private final Set<BookingId> reloadableBookingIds;
        private final List<Integer> queriedShards = new ArrayList<>();
        private int findByIdCalls;

        private TrackingBookingRepository(
                int failingShard,
                List<Booking> shardZero,
                List<Booking> shardOne,
                List<Booking> shardTwo,
                Set<BookingId> reloadableBookingIds) {
            this.failingShard = failingShard;
            this.bookingsByShard = List.of(shardZero, shardOne, shardTwo);
            this.reloadableBookingIds = reloadableBookingIds;
        }

        @Override
        public Optional<Booking> findById(BookingId bookingId) {
            findByIdCalls++;
            if (!reloadableBookingIds.contains(bookingId)) return Optional.empty();
            return bookingsByShard.stream()
                    .flatMap(List::stream)
                    .filter(booking -> booking.id().equals(bookingId))
                    .findFirst();
        }

        @Override
        public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) {
            queriedShards.add(shard);
            if (shard == failingShard) throw new RuntimeException("shard read unavailable");
            return shard < bookingsByShard.size() ? bookingsByShard.get(shard) : List.of();
        }

        @Override public Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, HoldId holdId, String key) { return Optional.empty(); }
        @Override public void savePaymentIntent(Booking booking) { throw new AssertionError("not expected"); }
        @Override public void rescheduleReconciliation(Booking booking) { throw new AssertionError("not expected"); }
    }

    private static final class UnusedHoldRepository implements HoldRepository {
        @Override public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) { throw new AssertionError("not expected"); }
        @Override public void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now, HoldIdempotencyKey key) { throw new AssertionError("not expected"); }
        @Override public Optional<Hold> findById(HoldId holdId) { throw new AssertionError("not expected"); }
        @Override public Optional<Hold> findByIdempotencyKey(HoldIdempotencyKey key) { throw new AssertionError("not expected"); }
    }

    private static final class UnusedCheckoutGateway implements CheckoutGateway {
        @Override public void startCheckout(ReservationCheckout reservation, Booking pendingBooking) { throw new AssertionError("not expected"); }
        @Override public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) { throw new AssertionError("not expected"); }
        @Override public void failBooking(ReservationCheckout reservation, Booking failedBooking) { throw new AssertionError("not expected"); }
    }

    private static final class UnusedPaymentGateway implements PaymentGateway {
        @Override public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey) { throw new AssertionError("not expected"); }
        @Override public PaymentIntentStatus getPaymentStatus(String paymentIntentId) { throw new AssertionError("not expected"); }
        @Override public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) { throw new AssertionError("not expected"); }
    }
}
