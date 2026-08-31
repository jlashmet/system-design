package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.AdmissionRequiredException;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyConflictException;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CreateHoldHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-27T22:00:00Z");
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final UserId OTHER_USER = new UserId("user-789");
    private static final SeatId A10 = new SeatId("A10");
    private static final SeatId A11 = new SeatId("A11");
    private static final SeatId A12 = new SeatId("A12");
    private static final Currency USD = Currency.getInstance("USD");
    private static final HoldIdempotencyKey KEY = new HoldIdempotencyKey("hold-request-1");
    private static final EventWriteAuthority LOCAL_OWNER = ignored -> {};

    private FakeHoldRepository holdRepository;
    private FakeWaitingRoomRepository waitingRoomRepository;
    private CreateHoldHandler handler;
    private Hold firstHold;
    private Hold result;
    private Throwable thrown;
    private int admissionReadsAfterFirst;

    @Test
    void computesHoldTotalFromAuthoritativeSeatQuoteWhenWaitingRoomIsDisabled() {
        givenDisabledWaitingRoom();
        whenDefaultHoldIsCreated();
        thenExpectAuthoritativePriceAndExpiry();
    }

    @Test
    void retryWithSameIdempotencyKeyReturnsOriginalHoldWithoutRepricingOrReadmission() {
        givenExistingDefaultHold();
        whenDefaultHoldIsRetried();
        thenExpectOriginalHoldWithoutRepricingOrReadmission();
    }

    @Test
    void reusingIdempotencyKeyForDifferentRequestInSameScopeIsConflict() {
        givenExistingDefaultHold();
        whenSameScopedKeyIsReusedForDifferentSeats();
        thenExpectIdempotencyConflict();
    }

    @Test
    void differentUserMayReuseSameClientIdempotencyKey() {
        givenExistingDefaultHold();
        whenOtherUserReusesSameKeyForDifferentSeat();
        thenExpectIndependentScopedHold();
    }

    @Test
    void rejectsWrongRegionBeforeIdempotencyWaitingRoomOrPricing() {
        givenWrongBookingRegion();
        whenDefaultHoldIsCreated();
        thenExpectWrongRegionBeforeAnyHoldWork();
    }

    @Test
    void rejectsHoldBeforePricingWhenWaitingRoomIsEnabledAndUserHasNotJoined() {
        givenEnabledWaitingRoomWithoutJoin();
        whenDefaultHoldIsCreated();
        thenExpectAdmissionRequiredBeforePricing();
    }

    @Test
    void rejectsHoldBeforePricingWhenUserIsStillBehindAdmissionWatermark() {
        givenWaitingUserBehindWatermark();
        whenDefaultHoldIsCreated();
        thenExpectAdmissionRequiredBeforePricing();
    }

    @Test
    void permitsHoldWhenUserIsAtOrBeforeAdmissionWatermark() {
        givenAdmittedWaitingRoomUser();
        whenDefaultHoldIsCreated();
        thenExpectHoldCreatedAfterAdmission();
    }

    private void givenDisabledWaitingRoom() {
        holdRepository = new FakeHoldRepository();
        waitingRoomRepository = new FakeWaitingRoomRepository();
        handler = handler(LOCAL_OWNER);
        resetResult();
    }

    private void givenExistingDefaultHold() {
        givenDisabledWaitingRoom();
        firstHold = handler.handle(defaultCommand());
        admissionReadsAfterFirst = waitingRoomRepository.admissionReads;
        resetResult();
    }

    private void givenWrongBookingRegion() {
        holdRepository = new FakeHoldRepository();
        waitingRoomRepository = new FakeWaitingRoomRepository();
        EventWriteAuthority wrongRegion = eventId -> {
            throw new WrongBookingRegionException(eventId, "us-east-1", "us-west-2");
        };
        handler = handler(wrongRegion);
        resetResult();
    }

    private void givenEnabledWaitingRoomWithoutJoin() {
        givenDisabledWaitingRoom();
        waitingRoomRepository.admission = new EventAdmission(EVENT_ID, NOW);
    }

    private void givenWaitingUserBehindWatermark() {
        givenEnabledWaitingRoomWithoutJoin();
        waitingRoomRepository.entry = new WaitingRoomEntry(EVENT_ID, USER_ID, NOW.plusSeconds(1));
    }

    private void givenAdmittedWaitingRoomUser() {
        givenEnabledWaitingRoomWithoutJoin();
        waitingRoomRepository.entry = new WaitingRoomEntry(EVENT_ID, USER_ID, NOW.minusSeconds(1));
    }

    private void whenDefaultHoldIsCreated() {
        capture(() -> handler.handle(defaultCommand()));
    }

    private void whenDefaultHoldIsRetried() {
        capture(() -> handler.handle(defaultCommand()));
    }

    private void whenSameScopedKeyIsReusedForDifferentSeats() {
        capture(() -> handler.handle(new CreateHoldCommand(USER_ID, EVENT_ID, List.of(A10), KEY)));
    }

    private void whenOtherUserReusesSameKeyForDifferentSeat() {
        capture(() -> handler.handle(new CreateHoldCommand(OTHER_USER, EVENT_ID, List.of(A12), KEY)));
    }

    private void thenExpectAuthoritativePriceAndExpiry() {
        assertThat(thrown).isNull();
        assertThat(result).isNotNull();
        assertThat(result.totalPrice()).isEqualTo(price("225.00"));
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(holdRepository.createdHold).isEqualTo(result);
        assertThat(holdRepository.claimQuote.totalPrice()).isEqualTo(price("225.00"));
        assertThat(holdRepository.createdKey).isEqualTo(KEY);
    }

    private void thenExpectOriginalHoldWithoutRepricingOrReadmission() {
        assertThat(thrown).isNull();
        assertThat(result).isEqualTo(firstHold);
        assertThat(holdRepository.quoteCalls).isOne();
        assertThat(waitingRoomRepository.admissionReads).isEqualTo(admissionReadsAfterFirst);
    }

    private void thenExpectIdempotencyConflict() {
        assertThat(thrown).isInstanceOf(HoldIdempotencyConflictException.class);
        assertThat(result).isNull();
        assertThat(holdRepository.quoteCalls).isOne();
    }

    private void thenExpectIndependentScopedHold() {
        assertThat(thrown).isNull();
        assertThat(result).isNotNull();
        assertThat(result.userId()).isEqualTo(OTHER_USER);
        assertThat(result.seatIds()).containsExactly(A12);
        assertThat(result).isNotEqualTo(firstHold);
        assertThat(holdRepository.findByIdempotencyKey(EVENT_ID, USER_ID, KEY)).contains(firstHold);
        assertThat(holdRepository.findByIdempotencyKey(EVENT_ID, OTHER_USER, KEY)).contains(result);
    }

    private void thenExpectWrongRegionBeforeAnyHoldWork() {
        assertThat(thrown).isInstanceOf(WrongBookingRegionException.class);
        assertThat(result).isNull();
        assertThat(holdRepository.idempotencyReads).isZero();
        assertThat(holdRepository.quoteCalls).isZero();
        assertThat(waitingRoomRepository.admissionReads).isZero();
        assertThat(holdRepository.createdHold).isNull();
    }

    private void thenExpectAdmissionRequiredBeforePricing() {
        assertThat(thrown).isInstanceOf(AdmissionRequiredException.class);
        assertThat(result).isNull();
        assertThat(holdRepository.quoteCalls).isZero();
        assertThat(holdRepository.createdHold).isNull();
    }

    private void thenExpectHoldCreatedAfterAdmission() {
        assertThat(thrown).isNull();
        assertThat(result).isNotNull();
        assertThat(holdRepository.quoteCalls).isOne();
        assertThat(holdRepository.createdHold).isEqualTo(result);
    }

    private CreateHoldHandler handler(EventWriteAuthority authority) {
        return new CreateHoldHandler(
                authority,
                holdRepository,
                waitingRoomRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));
    }

    private void capture(HoldOperation operation) {
        result = null;
        thrown = null;
        try {
            result = operation.run();
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void resetResult() {
        result = null;
        thrown = null;
    }

    private static CreateHoldCommand defaultCommand() {
        return new CreateHoldCommand(USER_ID, EVENT_ID, List.of(A10, A11), KEY);
    }

    private static Price price(String amount) {
        return new Price(new BigDecimal(amount), USD);
    }

    @FunctionalInterface
    private interface HoldOperation {
        Hold run();
    }

    private static final class FakeHoldRepository implements HoldRepository {
        private final Map<ScopedKey, Hold> byKey = new HashMap<>();
        private final Map<SeatId, Price> prices = Map.of(
                A10, price("100.00"),
                A11, price("125.00"),
                A12, price("150.00"));
        private Hold createdHold;
        private SeatPriceQuote claimQuote;
        private HoldIdempotencyKey createdKey;
        private int quoteCalls;
        private int idempotencyReads;

        @Override
        public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) {
            quoteCalls++;
            Map<SeatId, Price> quoted = new HashMap<>();
            for (SeatId seatId : seatIds) {
                Price price = prices.get(seatId);
                if (price == null) throw new AssertionError("missing fake price for " + seatId.value());
                quoted.put(seatId, price);
            }
            return new SeatPriceQuote(eventId, quoted);
        }

        @Override
        public void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now, HoldIdempotencyKey key) {
            createdHold = hold;
            claimQuote = quote;
            createdKey = key;
            byKey.put(new ScopedKey(hold.eventId(), hold.userId(), key), hold);
            assertThat(now).isEqualTo(NOW);
        }

        @Override
        public Optional<Hold> findById(HoldId holdId) {
            return byKey.values().stream().filter(hold -> hold.id().equals(holdId)).findFirst();
        }

        @Override
        public Optional<Hold> findByIdempotencyKey(
                EventId eventId, UserId userId, HoldIdempotencyKey key) {
            idempotencyReads++;
            return Optional.ofNullable(byKey.get(new ScopedKey(eventId, userId, key)));
        }

        @Override
        @Deprecated
        public Optional<Hold> findByIdempotencyKey(HoldIdempotencyKey key) {
            throw new AssertionError("unscoped idempotency lookup is not expected");
        }
    }

    private record ScopedKey(EventId eventId, UserId userId, HoldIdempotencyKey key) {}

    private static final class FakeWaitingRoomRepository implements WaitingRoomRepository {
        private WaitingRoomEntry entry;
        private EventAdmission admission;
        private int admissionReads;

        @Override
        public WaitingRoomEntry join(WaitingRoomEntry entry) {
            this.entry = entry;
            return entry;
        }

        @Override
        public Optional<WaitingRoomEntry> findEntry(EventId eventId, UserId userId) {
            return Optional.ofNullable(entry)
                    .filter(value -> value.eventId().equals(eventId) && value.userId().equals(userId));
        }

        @Override
        public Optional<EventAdmission> findAdmission(EventId eventId) {
            admissionReads++;
            return Optional.ofNullable(admission).filter(value -> value.eventId().equals(eventId));
        }

        @Override
        public EventAdmission advanceAdmission(EventAdmission admission) {
            this.admission = admission;
            return admission;
        }
    }
}
