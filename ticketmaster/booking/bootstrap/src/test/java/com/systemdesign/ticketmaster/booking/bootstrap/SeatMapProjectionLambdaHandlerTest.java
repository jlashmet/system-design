package com.systemdesign.ticketmaster.booking.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.systemdesign.ticketmaster.booking.application.ProjectSeatMapHandler;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import com.systemdesign.ticketmaster.booking.infrastructure.input.DynamoSeatInventoryStreamProjector;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SeatMapProjectionLambdaHandlerTest {
    private static final Instant HOLD_EXPIRES_AT = Instant.parse("2026-08-28T21:45:00Z");

    private CapturingSeatMapRepository repository;
    private SeatMapProjectionLambdaHandler handler;
    private DynamodbEvent event;
    private StreamsEventResponse response;

    @Test
    void adaptsSeatNewImageAndIgnoresNonSeatRecords() {
        givenSeatAndNonSeatStreamRecords();
        whenLambdaBatchIsHandled();
        thenExpectSeatProjectedWithHoldExpiry();
    }

    @Test
    void checkpointsSuccessfulPrefixAndRetriesFromFirstProjectionFailure() {
        givenThreeSeatRecordsWithSecondProjectionFailure();
        whenLambdaBatchIsHandled();
        thenExpectOnlyPrefixProjectedAndSecondSequenceRetried();
    }

    private void givenSeatAndNonSeatStreamRecords() {
        repository = new CapturingSeatMapRepository();
        handler = new SeatMapProjectionLambdaHandler(
                new DynamoSeatInventoryStreamProjector(new ProjectSeatMapHandler(repository)));
        event = new DynamodbEvent();
        event.setRecords(List.of(
                record("sequence-1", seatImage()),
                record("sequence-2", Map.of("entityType", string("HOLD")))));
        response = null;
    }

    private void givenThreeSeatRecordsWithSecondProjectionFailure() {
        repository = new CapturingSeatMapRepository();
        repository.failOnAttempt = 2;
        handler = new SeatMapProjectionLambdaHandler(
                new DynamoSeatInventoryStreamProjector(new ProjectSeatMapHandler(repository)));
        event = new DynamodbEvent();
        event.setRecords(List.of(
                record("sequence-1", seatImage("A10")),
                record("sequence-2", seatImage("A11")),
                record("sequence-3", seatImage("A12"))));
        response = null;
    }

    private void whenLambdaBatchIsHandled() {
        response = handler.handleRequest(event, null);
    }

    private void thenExpectSeatProjectedWithHoldExpiry() {
        assertThat(repository.projected).isNotNull();
        assertThat(repository.projected.eventId()).isEqualTo(new EventId("event-123"));
        assertThat(repository.projected.sectionId()).isEqualTo(new SectionId("101"));
        assertThat(repository.projected.seatId().value()).isEqualTo("A10");
        assertThat(repository.projected.price().amount()).isEqualByComparingTo(new BigDecimal("125.00"));
        assertThat(repository.projected.status().name()).isEqualTo("HELD");
        assertThat(repository.projected.holdExpiresAt()).isEqualTo(HOLD_EXPIRES_AT);
        assertThat(repository.upserts).isEqualTo(1);
        assertThat(failureIds()).isEmpty();
    }

    private void thenExpectOnlyPrefixProjectedAndSecondSequenceRetried() {
        assertThat(repository.attempts).isEqualTo(2);
        assertThat(repository.upserts).isEqualTo(1);
        assertThat(repository.projected.seatId().value()).isEqualTo("A10");
        assertThat(failureIds()).containsExactly("sequence-2");
    }

    private List<String> failureIds() {
        return response.getBatchItemFailures().stream()
                .map(StreamsEventResponse.BatchItemFailure::getItemIdentifier)
                .toList();
    }

    private static DynamodbEvent.DynamodbStreamRecord record(
            String sequenceNumber,
            Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> image) {
        com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord streamRecord =
                new com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord();
        streamRecord.setSequenceNumber(sequenceNumber);
        streamRecord.setNewImage(image);
        DynamodbEvent.DynamodbStreamRecord eventRecord = new DynamodbEvent.DynamodbStreamRecord();
        eventRecord.setDynamodb(streamRecord);
        return eventRecord;
    }

    private static Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> seatImage() {
        return seatImage("A10");
    }

    private static Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> seatImage(
            String seatId) {
        return Map.ofEntries(
                Map.entry("entityType", string("SEAT")),
                Map.entry("eventId", string("event-123")),
                Map.entry("sectionId", string("101")),
                Map.entry("seatId", string(seatId)),
                Map.entry("row", string("A")),
                Map.entry("number", string(seatId.substring(1))),
                Map.entry("priceAmount", string("125.00")),
                Map.entry("priceCurrency", string("USD")),
                Map.entry("status", string("HELD")),
                Map.entry("holdExpiresAt", number(HOLD_EXPIRES_AT.toEpochMilli())));
    }

    private static com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue string(String value) {
        return new com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue(value);
    }

    private static com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue number(long value) {
        com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue attribute =
                new com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue();
        attribute.setN(Long.toString(value));
        return attribute;
    }

    private static final class CapturingSeatMapRepository implements SeatMapRepository {
        private SeatMapSeat projected;
        private int attempts;
        private int upserts;
        private int failOnAttempt;

        @Override
        public void upsert(SeatMapSeat seat) {
            attempts++;
            if (attempts == failOnAttempt) throw new IllegalStateException("simulated seat-map write failure");
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
