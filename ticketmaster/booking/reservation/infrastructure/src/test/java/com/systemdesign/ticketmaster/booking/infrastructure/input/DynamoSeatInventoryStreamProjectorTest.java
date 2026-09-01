package com.systemdesign.ticketmaster.booking.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.application.ProjectSeatMapHandler;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SeatStatus;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;

class DynamoSeatInventoryStreamProjectorTest {
    private static final Instant HOLD_EXPIRES_AT = Instant.parse("2026-08-28T20:05:00Z");

    private FakeSeatMapRepository repository;
    private DynamoSeatInventoryStreamProjector projector;

    @Test
    void projectsAuthoritativeSeatImageIntoSectionReadModel() {
        givenSeatProjector();
        whenHeldSeatImageIsProjected();
        thenExpectSeatAndHoldExpiryProjected();
    }

    @Test
    void ignoresNonSeatAndRemoveImages() {
        givenSeatProjector();
        whenNonSeatAndRemoveImagesAreProjected();
        thenExpectNothingProjected();
    }

    private void givenSeatProjector() {
        repository = new FakeSeatMapRepository();
        projector = new DynamoSeatInventoryStreamProjector(new ProjectSeatMapHandler(repository));
    }

    private void whenHeldSeatImageIsProjected() {
        projector.project(StreamRecord.builder().newImage(Map.of(
                "entityType", string("SEAT"),
                "eventId", string("event-1"),
                "sectionId", string("section-101"),
                "seatId", string("A10"),
                "row", string("A"),
                "number", string("10"),
                "priceAmount", string("125.50"),
                "priceCurrency", string("USD"),
                "status", string("HELD"),
                "holdExpiresAt", number(HOLD_EXPIRES_AT.toEpochMilli()))).build());
    }

    private void whenNonSeatAndRemoveImagesAreProjected() {
        projector.project(StreamRecord.builder().newImage(Map.of("entityType", string("HOLD"))).build());
        projector.project(StreamRecord.builder().build());
    }

    private void thenExpectSeatAndHoldExpiryProjected() {
        assertThat(repository.projected).singleElement().satisfies(seat -> {
            assertThat(seat.eventId().value()).isEqualTo("event-1");
            assertThat(seat.sectionId().value()).isEqualTo("section-101");
            assertThat(seat.seatId().value()).isEqualTo("A10");
            assertThat(seat.row()).isEqualTo("A");
            assertThat(seat.number()).isEqualTo("10");
            assertThat(seat.price().amount()).isEqualByComparingTo(new BigDecimal("125.50"));
            assertThat(seat.price().currency().getCurrencyCode()).isEqualTo("USD");
            assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
            assertThat(seat.holdExpiresAt()).isEqualTo(HOLD_EXPIRES_AT);
        });
    }

    private void thenExpectNothingProjected() {
        assertThat(repository.projected).isEmpty();
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private static final class FakeSeatMapRepository implements SeatMapRepository {
        private final List<SeatMapSeat> projected = new ArrayList<>();

        @Override
        public void upsert(SeatMapSeat seat) {
            projected.add(seat);
        }

        @Override
        public List<SectionId> findSections(EventId eventId) {
            return List.of();
        }

        @Override
        public List<SeatMapSeat> findSection(EventId eventId, SectionId sectionId) {
            return projected.stream()
                    .filter(seat -> seat.eventId().equals(eventId) && seat.sectionId().equals(sectionId))
                    .toList();
        }
    }
}
