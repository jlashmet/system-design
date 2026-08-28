package com.systemdesign.ticketmaster.booking.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.application.ProjectSeatMapHandler;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SeatStatus;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;

class DynamoSeatInventoryStreamProjectorTest {

    @Test
    void projectsAuthoritativeSeatImageIntoSectionReadModel() {
        FakeSeatMapRepository repository = new FakeSeatMapRepository();
        DynamoSeatInventoryStreamProjector projector = new DynamoSeatInventoryStreamProjector(
                new ProjectSeatMapHandler(repository));

        projector.project(StreamRecord.builder().newImage(Map.of(
                "entityType", string("SEAT"),
                "eventId", string("event-1"),
                "sectionId", string("section-101"),
                "seatId", string("A10"),
                "row", string("A"),
                "number", string("10"),
                "priceAmount", string("125.50"),
                "priceCurrency", string("USD"),
                "status", string("HELD"))).build());

        assertThat(repository.projected).singleElement().satisfies(seat -> {
            assertThat(seat.eventId().value()).isEqualTo("event-1");
            assertThat(seat.sectionId().value()).isEqualTo("section-101");
            assertThat(seat.seatId().value()).isEqualTo("A10");
            assertThat(seat.row()).isEqualTo("A");
            assertThat(seat.number()).isEqualTo("10");
            assertThat(seat.price().amount()).isEqualByComparingTo(new BigDecimal("125.50"));
            assertThat(seat.price().currency().getCurrencyCode()).isEqualTo("USD");
            assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
        });
    }

    @Test
    void ignoresNonSeatAndRemoveImages() {
        FakeSeatMapRepository repository = new FakeSeatMapRepository();
        DynamoSeatInventoryStreamProjector projector = new DynamoSeatInventoryStreamProjector(
                new ProjectSeatMapHandler(repository));

        projector.project(StreamRecord.builder().newImage(Map.of("entityType", string("HOLD"))).build());
        projector.project(StreamRecord.builder().build());

        assertThat(repository.projected).isEmpty();
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static final class FakeSeatMapRepository implements SeatMapRepository {
        private final List<SeatMapSeat> projected = new ArrayList<>();

        @Override
        public void upsert(SeatMapSeat seat) {
            projected.add(seat);
        }

        @Override
        public List<SeatMapSeat> findSection(EventId eventId, SectionId sectionId) {
            return projected.stream()
                    .filter(seat -> seat.eventId().equals(eventId) && seat.sectionId().equals(sectionId))
                    .toList();
        }
    }
}
