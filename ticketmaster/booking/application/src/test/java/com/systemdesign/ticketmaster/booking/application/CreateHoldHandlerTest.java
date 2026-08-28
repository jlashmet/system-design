package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.AdmissionRequiredException;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CreateHoldHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-27T22:00:00Z");
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final SeatId A10 = new SeatId("A10");
    private static final SeatId A11 = new SeatId("A11");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void computesHoldTotalFromAuthoritativeSeatQuoteWhenWaitingRoomIsDisabled() {
        SeatPriceQuote quote = new SeatPriceQuote(EVENT_ID, Map.of(
                A10, price("100.00"),
                A11, price("125.00")));
        FakeHoldRepository holdRepository = new FakeHoldRepository(quote);
        FakeWaitingRoomRepository waitingRoomRepository = new FakeWaitingRoomRepository();
        CreateHoldHandler handler = handler(holdRepository, waitingRoomRepository);

        Hold hold = handler.handle(command());

        assertThat(hold.totalPrice()).isEqualTo(price("225.00"));
        assertThat(hold.createdAt()).isEqualTo(NOW);
        assertThat(hold.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(holdRepository.createdHold).isEqualTo(hold);
        assertThat(holdRepository.claimQuote).isEqualTo(quote);
    }

    @Test
    void rejectsHoldBeforePricingWhenWaitingRoomIsEnabledAndUserHasNotJoined() {
        FakeHoldRepository holdRepository = new FakeHoldRepository(defaultQuote());
        FakeWaitingRoomRepository waitingRoomRepository = new FakeWaitingRoomRepository();
        waitingRoomRepository.admission = new EventAdmission(EVENT_ID, NOW);
        CreateHoldHandler handler = handler(holdRepository, waitingRoomRepository);

        assertThatThrownBy(() -> handler.handle(command()))
                .isInstanceOf(AdmissionRequiredException.class);

        assertThat(holdRepository.quoteCalls).isZero();
        assertThat(holdRepository.createdHold).isNull();
    }

    @Test
    void rejectsHoldBeforePricingWhenUserIsStillBehindAdmissionWatermark() {
        FakeHoldRepository holdRepository = new FakeHoldRepository(defaultQuote());
        FakeWaitingRoomRepository waitingRoomRepository = new FakeWaitingRoomRepository();
        waitingRoomRepository.admission = new EventAdmission(EVENT_ID, NOW);
        waitingRoomRepository.entry = new WaitingRoomEntry(EVENT_ID, USER_ID, NOW.plusSeconds(1));
        CreateHoldHandler handler = handler(holdRepository, waitingRoomRepository);

        assertThatThrownBy(() -> handler.handle(command()))
                .isInstanceOf(AdmissionRequiredException.class);

        assertThat(holdRepository.quoteCalls).isZero();
    }

    @Test
    void permitsHoldWhenUserIsAtOrBeforeAdmissionWatermark() {
        FakeHoldRepository holdRepository = new FakeHoldRepository(defaultQuote());
        FakeWaitingRoomRepository waitingRoomRepository = new FakeWaitingRoomRepository();
        waitingRoomRepository.admission = new EventAdmission(EVENT_ID, NOW);
        waitingRoomRepository.entry = new WaitingRoomEntry(EVENT_ID, USER_ID, NOW.minusSeconds(1));
        CreateHoldHandler handler = handler(holdRepository, waitingRoomRepository);

        Hold hold = handler.handle(command());

        assertThat(holdRepository.quoteCalls).isOne();
        assertThat(holdRepository.createdHold).isEqualTo(hold);
    }

    private static CreateHoldHandler handler(
            HoldRepository holdRepository,
            WaitingRoomRepository waitingRoomRepository) {
        return new CreateHoldHandler(
                holdRepository,
                waitingRoomRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));
    }

    private static CreateHoldCommand command() {
        return new CreateHoldCommand(USER_ID, EVENT_ID, java.util.List.of(A10, A11));
    }

    private static SeatPriceQuote defaultQuote() {
        return new SeatPriceQuote(EVENT_ID, Map.of(A10, price("100.00"), A11, price("125.00")));
    }

    private static Price price(String amount) {
        return new Price(new BigDecimal(amount), USD);
    }

    private static final class FakeHoldRepository implements HoldRepository {
        private final SeatPriceQuote quote;
        private Hold createdHold;
        private SeatPriceQuote claimQuote;
        private int quoteCalls;

        private FakeHoldRepository(SeatPriceQuote quote) {
            this.quote = quote;
        }

        @Override
        public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) {
            quoteCalls++;
            assertThat(eventId).isEqualTo(quote.eventId());
            assertThat(seatIds).isEqualTo(quote.seatIds());
            return quote;
        }

        @Override
        public void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now) {
            this.createdHold = hold;
            this.claimQuote = quote;
            assertThat(now).isEqualTo(NOW);
        }

        @Override
        public Optional<Hold> findById(HoldId holdId) {
            return Optional.ofNullable(createdHold).filter(hold -> hold.id().equals(holdId));
        }
    }

    private static final class FakeWaitingRoomRepository implements WaitingRoomRepository {
        private WaitingRoomEntry entry;
        private EventAdmission admission;

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
            return Optional.ofNullable(admission).filter(value -> value.eventId().equals(eventId));
        }

        @Override
        public EventAdmission advanceAdmission(EventAdmission admission) {
            this.admission = admission;
            return admission;
        }
    }
}
