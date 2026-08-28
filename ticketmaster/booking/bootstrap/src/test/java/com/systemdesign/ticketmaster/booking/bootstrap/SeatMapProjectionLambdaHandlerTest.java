package com.systemdesign.ticketmaster.booking.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.systemdesign.ticketmaster.booking.application.ProjectSeatMapHandler;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import com.systemdesign.ticketmaster.booking.infrastructure.input.DynamoSeatInventoryStreamProjector;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SeatMapProjectionLambdaHandlerTest {

    @Test
    void adaptsSeatNewImageAndIgnoresNonSeatRecords() {
        CapturingSeatMapRepository repository = new CapturingSeatMapRepository();
        SeatMapProjectionLambdaHandler handler = new SeatMapProjectionLambdaHandler(
                new DynamoSeatInventoryStreamProjector(new ProjectSeatMapHandler(repository)));
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record(seatImage()), record(Map.of("entityType", string("HOLD")))));

        handler.handleRequest(event, null);

        assertThat(repository.projected).isNotNull();
        assertThat(repository.projected.eventId()).isEqualTo(new EventId("event-123"));
        assertThat(repository.projected.sectionId()).isEqualTo(new SectionId("101"));
        assertThat(repository.projected.seatId().value()).isEqualTo("A10");
        assertThat(repository.projected.price().amount()).isEqualByComparingTo(new BigDecimal("125.00"));
        assertThat(repository.projected.status().name()).isEqualTo("HELD");
        assertThat(repository.upserts).isEqualTo(1);
    }

    private static DynamodbEvent.DynamodbStreamRecord record(
            Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> image) {
        com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord streamRecord =
                new com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord();
        streamRecord.setNewImage(image);
        DynamodbEvent.DynamodbStreamRecord eventRecord = new DynamodbEvent.DynamodbStreamRecord();
        eventRecord.setDynamodb(streamRecord);
        return eventRecord;
    }

    private static Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> seatImage() {
        return Map.of(
                "entityType", string("SEAT"),
                "eventId", string("event-123"),
                "sectionId", string("101"),
                "seatId", string("A10"),
                "row", string("A"),
                "number", string("10"),
                "priceAmount", string("125.00"),
                "priceCurrency", string("USD"),
                "status", string("HELD"));
    }

    private static com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue string(String value) {
        return new com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue(value);
    }

    private static final class CapturingSeatMapRepository implements SeatMapRepository {
        private SeatMapSeat projected;
        private int upserts;

        @Override
        public void upsert(SeatMapSeat seat) {
            projected = seat;
            upserts++;
        }

        @Override
        public List<SectionId> findSections(EventId eventId) {
            return List.of();
        }

        @Override
        public List<SeatMapSeat> findSection(EventId eventId, SectionId sectionId) {
            return List.of();
        }
    }
}
