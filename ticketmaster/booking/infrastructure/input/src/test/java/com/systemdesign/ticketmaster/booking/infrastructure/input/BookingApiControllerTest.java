package com.systemdesign.ticketmaster.booking.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.api.model.CheckoutResponse;
import com.systemdesign.ticketmaster.booking.api.model.SectionResponse;
import com.systemdesign.ticketmaster.booking.api.model.StartCheckoutRequest;
import com.systemdesign.ticketmaster.booking.application.GetSectionsHandler;
import com.systemdesign.ticketmaster.booking.application.StartCheckoutHandler;
import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import com.systemdesign.ticketmaster.booking.domain.PreparedCheckout;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutService;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class BookingApiControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-28T18:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(600);
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    @Test
    void mapsProjectedSectionsToApiContractWithShortSharedCachePolicy() {
        BookingApiController controller = new BookingApiController(
                null,
                new GetSectionsHandler(new FakeSeatMapRepository()),
                null);

        ResponseEntity<List<SectionResponse>> response = controller.getSections("event-123");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("public, max-age=60, stale-while-revalidate=300");
        assertThat(response.getBody()).extracting(SectionResponse::getSectionId)
                .containsExactly("101", "102");
    }

    @Test
    void nextUsesTrustedHeaderIdentityAndSelectedSeatsToStartCheckout() {
        CapturingReservationService reservations = new CapturingReservationService();
        StartCheckoutHandler handler = new StartCheckoutHandler(
                ignored -> {},
                reservations,
                new InMemoryBookingRepository(),
                new RecordingCheckoutGateway(),
                new FixedPaymentGateway(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                16);
        BookingApiController controller = new BookingApiController(handler, null, null);
        StartCheckoutRequest request = new StartCheckoutRequest();
        request.setSeatIds(Set.of("A10"));

        ResponseEntity<CheckoutResponse> response = controller.startCheckout(
                "event-123", "checkout-key", "user-header", request, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCheckoutExpiresAt().toInstant()).isEqualTo(DEADLINE);
        assertThat(reservations.userId).isEqualTo(new UserId("user-header"));
        assertThat(reservations.seatIds).containsExactly(new SeatId("A10"));
    }

    private static final class FakeSeatMapRepository implements SeatMapRepository {
        @Override public void upsert(SeatMapSeat seat) {}
        @Override
        public List<SectionId> findSections(EventId eventId) {
            assertThat(eventId).isEqualTo(new EventId("event-123"));
            return List.of(new SectionId("101"), new SectionId("102"));
        }
        @Override public List<SeatMapSeat> findSection(EventId eventId, SectionId sectionId) { return List.of(); }
    }

    private static final class CapturingReservationService implements ReservationCheckoutService {
        private UserId userId;
        private Set<SeatId> seatIds;
        private ReservationCheckout reservation;

        @Override
        public PreparedCheckout prepareCheckout(EventId eventId, UserId userId, Set<SeatId> seatIds,
                                                String admissionToken, Instant now, Instant checkoutExpiresAt) {
            this.userId = userId;
            this.seatIds = Set.copyOf(seatIds);
            reservation = new ReservationCheckout(
                    new HoldId("checkout-1"), userId, eventId, seatIds, PRICE,
                    ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS, checkoutExpiresAt);
            return new PreparedCheckout(reservation, Map.of(new SeatId("A10"), PRICE));
        }

        @Override
        public Optional<ReservationCheckout> findById(HoldId holdId) {
            return reservation != null && reservation.id().equals(holdId)
                    ? Optional.of(reservation) : Optional.empty();
        }
    }

    private static final class InMemoryBookingRepository implements BookingRepository {
        private Booking booking;
        @Override public Optional<Booking> findById(BookingId bookingId) { return Optional.ofNullable(booking); }
        @Override
        public Optional<Booking> findByCheckoutIdempotencyKey(EventId eventId, UserId userId, String key) {
            return booking != null && booking.eventId().equals(eventId)
                    && booking.userId().equals(userId)
                    && booking.checkoutIdempotencyKey().equals(key)
                    ? Optional.of(booking) : Optional.empty();
        }
        @Override public void savePaymentIntent(Booking booking) { this.booking = booking; }
        @Override public void rescheduleReconciliation(Booking booking) { this.booking = booking; }
        @Override public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) { return List.of(); }
    }

    private static final class RecordingCheckoutGateway implements CheckoutGateway {
        @Override public void startCheckout(PreparedCheckout preparedCheckout, Booking pendingBooking) {}
        @Override public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) {}
        @Override public void failBooking(ReservationCheckout reservation, Booking failedBooking) {}
    }

    private static final class FixedPaymentGateway implements PaymentGateway {
        @Override
        public PaymentIntent createPaymentIntent(EventId eventId, BookingId bookingId, Price price, String key) {
            return new PaymentIntent("pi-1", PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
        }
        @Override
        public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String key) {
            return createPaymentIntent(new EventId("event-123"), bookingId, price, key);
        }
        @Override public PaymentIntentStatus getPaymentStatus(String paymentIntentId) { return PaymentIntentStatus.REQUIRES_PAYMENT_METHOD; }
        @Override public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) { return PaymentIntentStatus.CANCELED; }
    }
}
