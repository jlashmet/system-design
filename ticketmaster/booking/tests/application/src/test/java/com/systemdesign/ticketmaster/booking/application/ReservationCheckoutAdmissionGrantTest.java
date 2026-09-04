package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.AdmissionGrant;
import com.systemdesign.ticketmaster.booking.domain.AdmissionGrantService;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.PreparedCheckout;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReservationCheckoutAdmissionGrantTest {
    private static final Instant NOW = Instant.parse("2026-08-29T13:30:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(600);
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final SeatId SEAT_ID = new SeatId("A10");
    private static final Price PRICE = new Price(new BigDecimal("100.00"), Currency.getInstance("USD"));

    @Test
    void validGrantSkipsWaitingRoomReadsAndProducesCheckoutReservation() {
        TrackingWaitingRoomRepository waitingRoom = admittedWaitingRoom();
        ReservationCheckoutServiceImpl service = service(waitingRoom, new StubGrantService(true, false));

        PreparedCheckout prepared = service.prepareCheckout(
                EVENT_ID, USER_ID, Set.of(SEAT_ID), "grant-123", NOW, DEADLINE);

        assertThat(prepared.reservation().status()).isEqualTo(ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS);
        assertThat(prepared.reservation().checkoutExpiresAt()).isEqualTo(DEADLINE);
        assertThat(prepared.reservation().totalPrice()).isEqualTo(PRICE);
        assertThat(prepared.seatPrices()).containsEntry(SEAT_ID, PRICE);
        assertThat(waitingRoom.admissionReads).isZero();
        assertThat(waitingRoom.entryReads).isZero();
    }

    @Test
    void invalidGrantFallsBackToAuthoritativeAdmissionReadsBeforePricing() {
        TrackingWaitingRoomRepository waitingRoom = admittedWaitingRoom();
        TrackingHoldRepository holds = new TrackingHoldRepository();
        ReservationCheckoutServiceImpl service = new ReservationCheckoutServiceImpl(
                holds, new AdmissionAccessService(waitingRoom, new StubGrantService(false, false)));

        service.prepareCheckout(EVENT_ID, USER_ID, Set.of(SEAT_ID), "bad-grant", NOW, DEADLINE);

        assertThat(waitingRoom.admissionReads).isOne();
        assertThat(waitingRoom.entryReads).isOne();
        assertThat(holds.quoteCalls).isOne();
    }

    @Test
    void grantVerifierFailureFallsBackToAuthoritativeAdmissionReads() {
        TrackingWaitingRoomRepository waitingRoom = admittedWaitingRoom();
        ReservationCheckoutServiceImpl service = service(waitingRoom, new StubGrantService(false, true));

        service.prepareCheckout(EVENT_ID, USER_ID, Set.of(SEAT_ID), "grant-service-down", NOW, DEADLINE);

        assertThat(waitingRoom.admissionReads).isOne();
        assertThat(waitingRoom.entryReads).isOne();
    }

    private static ReservationCheckoutServiceImpl service(
            TrackingWaitingRoomRepository waitingRoom, AdmissionGrantService grants) {
        return new ReservationCheckoutServiceImpl(
                new TrackingHoldRepository(), new AdmissionAccessService(waitingRoom, grants));
    }

    private static TrackingWaitingRoomRepository admittedWaitingRoom() {
        TrackingWaitingRoomRepository waitingRoom = new TrackingWaitingRoomRepository();
        waitingRoom.admission = new EventAdmission(EVENT_ID, NOW);
        waitingRoom.entry = new WaitingRoomEntry(EVENT_ID, USER_ID, NOW.minusSeconds(1));
        return waitingRoom;
    }

    private static final class StubGrantService implements AdmissionGrantService {
        private final boolean accepts;
        private final boolean throwsFailure;

        private StubGrantService(boolean accepts, boolean throwsFailure) {
            this.accepts = accepts;
            this.throwsFailure = throwsFailure;
        }

        @Override public Optional<AdmissionGrant> issue(EventId eventId, UserId userId, Instant now) { return Optional.empty(); }

        @Override
        public boolean accepts(EventId eventId, UserId userId, String token, Instant now) {
            if (throwsFailure) throw new IllegalStateException("grant verifier unavailable");
            return accepts;
        }
    }

    private static final class TrackingHoldRepository implements HoldRepository {
        private int quoteCalls;

        @Override
        public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) {
            quoteCalls++;
            return new SeatPriceQuote(eventId, Map.of(SEAT_ID, PRICE));
        }

        @Override public Optional<Hold> findById(HoldId holdId) { return Optional.empty(); }
    }

    private static final class TrackingWaitingRoomRepository implements WaitingRoomRepository {
        private EventAdmission admission;
        private WaitingRoomEntry entry;
        private int admissionReads;
        private int entryReads;

        @Override
        public Optional<EventAdmission> findAdmission(EventId eventId) {
            admissionReads++;
            return Optional.ofNullable(admission);
        }

        @Override
        public Optional<WaitingRoomEntry> findEntry(EventId eventId, UserId userId) {
            entryReads++;
            return Optional.ofNullable(entry);
        }

        @Override public WaitingRoomEntry join(WaitingRoomEntry entry) { this.entry = entry; return entry; }
        @Override public EventAdmission advanceAdmission(EventAdmission admission) { this.admission = admission; return admission; }
    }
}
