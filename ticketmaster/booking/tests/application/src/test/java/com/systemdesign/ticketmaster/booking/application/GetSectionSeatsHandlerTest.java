package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SeatStatus;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class GetSectionSeatsHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-28T20:00:00Z");
    private static final EventId EVENT_ID = new EventId("event-1");
    private static final SectionId SECTION_ID = new SectionId("101");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    private GetSectionSeatsHandler handler;
    private List<SeatMapSeat> result;

    @Test
    void derivesDisplayAvailabilityFromOrdinaryHoldExpiryWithoutReleasingCheckout() {
        givenSeatMapWithExpiredHoldActiveHoldAndCheckout();
        whenSectionSeatsAreRead();
        thenExpectOnlyExpiredOrdinaryHoldDisplayedAvailable();
    }

    private void givenSeatMapWithExpiredHoldActiveHoldAndCheckout() {
        SeatMapRepository repository = new FixedSeatMapRepository(List.of(
                seat("A10", SeatStatus.HELD, NOW.minusSeconds(1)),
                seat("A11", SeatStatus.HELD, NOW.plusSeconds(30)),
                seat("A12", SeatStatus.CHECKOUT, NOW.minusSeconds(1))));
        handler = new GetSectionSeatsHandler(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        result = null;
    }

    private void whenSectionSeatsAreRead() {
        result = handler.handle(new GetSectionSeatsQuery(EVENT_ID, SECTION_ID));
    }

    private void thenExpectOnlyExpiredOrdinaryHoldDisplayedAvailable() {
        assertThat(result).extracting(SeatMapSeat::status)
                .containsExactly(SeatStatus.AVAILABLE, SeatStatus.HELD, SeatStatus.CHECKOUT);
        assertThat(result.get(0).holdExpiresAt()).isNull();
        assertThat(result.get(1).holdExpiresAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(result.get(2).holdExpiresAt()).isEqualTo(NOW.minusSeconds(1));
    }

    private static SeatMapSeat seat(String seatId, SeatStatus status, Instant holdExpiresAt) {
        return new SeatMapSeat(
                EVENT_ID,
                SECTION_ID,
                new SeatId(seatId),
                "A",
                seatId.substring(1),
                PRICE,
                status,
                holdExpiresAt);
    }

    private record FixedSeatMapRepository(List<SeatMapSeat> seats) implements SeatMapRepository {
        @Override public void upsert(SeatMapSeat seat) { throw new AssertionError("not expected"); }
        @Override public List<SectionId> findSections(EventId eventId) { return List.of(); }
        @Override public List<SeatMapSeat> findSection(EventId eventId, SectionId sectionId) {
            assertThat(eventId).isEqualTo(EVENT_ID);
            assertThat(sectionId).isEqualTo(SECTION_ID);
            return seats;
        }
    }
}
