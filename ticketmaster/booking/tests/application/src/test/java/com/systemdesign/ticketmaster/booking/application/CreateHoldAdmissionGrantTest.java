package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.AdmissionGrant;
import com.systemdesign.ticketmaster.booking.domain.AdmissionGrantService;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
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

class CreateHoldAdmissionGrantTest {
    private static final Instant NOW = Instant.parse("2026-08-29T13:30:00Z");
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final SeatId SEAT_ID = new SeatId("A10");
    private static final Price PRICE = new Price(new BigDecimal("100.00"), Currency.getInstance("USD"));

    private TrackingWaitingRoomRepository waitingRoom;
    private CreateHoldHandler handler;
    private Hold result;
    private Throwable thrown;

    @Test
    void validGrantSkipsWaitingRoomReads() {
        givenGrantService(true, false);
        whenHoldCreated("grant-123");
        thenExpectCreatedWithWaitingRoomReads(0, 0);
    }

    @Test
    void invalidGrantFallsBackToAuthoritativeAdmissionReads() {
        givenGrantService(false, false);
        whenHoldCreated("bad-grant");
        thenExpectCreatedWithWaitingRoomReads(1, 1);
    }

    @Test
    void grantVerifierFailureFallsBackToAuthoritativeAdmissionReads() {
        givenGrantService(false, true);
        whenHoldCreated("grant-service-down");
        thenExpectCreatedWithWaitingRoomReads(1, 1);
    }

    private void givenGrantService(boolean accepts, boolean throwsFailure) {
        waitingRoom = new TrackingWaitingRoomRepository();
        waitingRoom.admission = new EventAdmission(EVENT_ID, NOW);
        waitingRoom.entry = new WaitingRoomEntry(EVENT_ID, USER_ID, NOW.minusSeconds(1));
        AdmissionGrantService grants = new StubGrantService(accepts, throwsFailure);
        handler = new CreateHoldHandler(
                ignored -> {},
                new FixedHoldRepository(),
                waitingRoom,
                grants,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));
        result = null;
        thrown = null;
    }

    private void whenHoldCreated(String admissionToken) {
        try {
            result = handler.handle(new CreateHoldCommand(
                    USER_ID,
                    EVENT_ID,
                    List.of(SEAT_ID),
                    new HoldIdempotencyKey("hold-key"),
                    admissionToken));
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectCreatedWithWaitingRoomReads(int admissionReads, int entryReads) {
        assertThat(thrown).isNull();
        assertThat(result).isNotNull();
        assertThat(waitingRoom.admissionReads).isEqualTo(admissionReads);
        assertThat(waitingRoom.entryReads).isEqualTo(entryReads);
    }

    private static final class StubGrantService implements AdmissionGrantService {
        private final boolean accepts;
        private final boolean throwsFailure;

        private StubGrantService(boolean accepts, boolean throwsFailure) {
            this.accepts = accepts;
            this.throwsFailure = throwsFailure;
        }

        @Override
        public Optional<AdmissionGrant> issue(EventId eventId, UserId userId, Instant now) {
            return Optional.empty();
        }

        @Override
        public boolean accepts(EventId eventId, UserId userId, String token, Instant now) {
            if (throwsFailure) throw new IllegalStateException("grant verifier unavailable");
            return accepts;
        }
    }

    private static final class FixedHoldRepository implements HoldRepository {
        @Override
        public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) {
            return new SeatPriceQuote(eventId, Map.of(SEAT_ID, PRICE));
        }

        @Override
        public void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now, HoldIdempotencyKey key) {
        }

        @Override
        public Optional<Hold> findById(HoldId holdId) {
            return Optional.empty();
        }

        @Override
        public Optional<Hold> findByIdempotencyKey(HoldIdempotencyKey key) {
            return Optional.empty();
        }
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

        @Override
        public WaitingRoomEntry join(WaitingRoomEntry entry) {
            this.entry = entry;
            return entry;
        }

        @Override
        public EventAdmission advanceAdmission(EventAdmission admission) {
            this.admission = admission;
            return admission;
        }
    }
}
