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
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class GetSectionSeatsHandlerTest {
    private static final EventId EVENT_ID = new EventId("event-1");
    private static final SectionId SECTION_ID = new SectionId("101");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    @Test
    void returnsAuthoritativeSeatStatusesWithoutClientSideExpiryRewrites() {
        SeatMapRepository repository = new FixedSeatMapRepository(List.of(
                seat("A10", SeatStatus.AVAILABLE),
                seat("A11", SeatStatus.CHECKOUT),
                seat("A12", SeatStatus.BOOKED)));
        GetSectionSeatsHandler handler = new GetSectionSeatsHandler(repository);

        List<SeatMapSeat> result = handler.handle(new GetSectionSeatsQuery(EVENT_ID, SECTION_ID));

        assertThat(result).extracting(SeatMapSeat::status)
                .containsExactly(SeatStatus.AVAILABLE, SeatStatus.CHECKOUT, SeatStatus.BOOKED);
    }

    private static SeatMapSeat seat(String seatId, SeatStatus status) {
        return new SeatMapSeat(
                EVENT_ID,
                SECTION_ID,
                new SeatId(seatId),
                "A",
                seatId.substring(1),
                PRICE,
                status);
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
