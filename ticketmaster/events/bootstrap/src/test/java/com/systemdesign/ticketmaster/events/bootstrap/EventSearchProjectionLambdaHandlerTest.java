package com.systemdesign.ticketmaster.events.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import com.systemdesign.ticketmaster.events.application.BuildEventSearchProjectionHandler;
import com.systemdesign.ticketmaster.events.application.DeleteEventSearchProjection;
import com.systemdesign.ticketmaster.events.application.EventSearchProjection;
import com.systemdesign.ticketmaster.events.application.EventSearchProjectionAction;
import com.systemdesign.ticketmaster.events.domain.Event;
import com.systemdesign.ticketmaster.events.domain.EventId;
import com.systemdesign.ticketmaster.events.domain.EventStatus;
import com.systemdesign.ticketmaster.events.domain.Venue;
import com.systemdesign.ticketmaster.events.domain.VenueId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EventSearchProjectionLambdaHandlerTest {
    private static final EventId EVENT_ID = new EventId("event-1");
    private static final VenueId VENUE_ID = new VenueId("venue-1");
    private static final Venue VENUE = new Venue(VENUE_ID, "SoFi Stadium", "Los Angeles");

    private CapturingPublisher publisher;
    private EventSearchProjectionLambdaHandler handler;
    private DynamodbEvent streamEvent;
    private StreamsEventResponse response;

    @Test
    void publishesUpsertForEventInsertOrModify() {
        givenCanonicalEvent(EventStatus.SCHEDULED);
        whenStreamRecordIsProcessed("MODIFY", "stream-1", eventImage(), null);
        thenExpectUpsert("stream-1");
    }

    @Test
    void publishesDeleteWhenCanonicalEventIsCancelled() {
        givenCanonicalEvent(EventStatus.CANCELLED);
        whenStreamRecordIsProcessed("MODIFY", "stream-2", eventImage(), null);
        thenExpectDelete("stream-2");
    }

    @Test
    void publishesDeleteDirectlyForRemovedEventRow() {
        givenMissingCanonicalEvent();
        whenStreamRecordIsProcessed("REMOVE", "stream-3", null, eventImage());
        thenExpectDelete("stream-3");
    }

    @Test
    void publishesDeleteFromEventKeyWhenOldImageIsUnavailable() {
        givenMissingCanonicalEvent();
        whenRemovalWithOnlyEventKeyIsProcessed();
        thenExpectDelete("stream-keys");
    }

    @Test
    void ignoresNonEventRows() {
        givenCanonicalEvent(EventStatus.SCHEDULED);
        whenStreamRecordIsProcessed("MODIFY", "stream-4", venueImage(), null);
        thenExpectNoProjection();
    }

    @Test
    void checkpointsSuccessfulPrefixAndRetriesFromFirstPublishFailure() {
        givenPublisherFailureOnSecondRecord();
        whenThreeRecordBatchIsProcessed();
        thenExpectOnlyPrefixPublishedAndSecondSequenceRetried();
    }

    private void givenCanonicalEvent(EventStatus status) {
        publisher = new CapturingPublisher();
        handler = handler(event(status), publisher);
        streamEvent = null;
        response = null;
    }

    private void givenMissingCanonicalEvent() {
        publisher = new CapturingPublisher();
        handler = handler(null, publisher);
        streamEvent = null;
        response = null;
    }

    private void givenPublisherFailureOnSecondRecord() {
        givenCanonicalEvent(EventStatus.SCHEDULED);
        publisher.failDeduplicationId = "stream-2";
    }

    private void whenStreamRecordIsProcessed(
            String eventName,
            String eventId,
            Map<String, AttributeValue> newImage,
            Map<String, AttributeValue> oldImage) {
        streamEvent = event(record(eventName, eventId, newImage, oldImage));
        response = handler.handleRequest(streamEvent, null);
    }

    private void whenRemovalWithOnlyEventKeyIsProcessed() {
        DynamodbEvent.DynamodbStreamRecord record = record("REMOVE", "stream-keys", null, null);
        record.getDynamodb().setKeys(Map.of("pk", string("EVENT#event-1")));
        streamEvent = event(record);
        response = handler.handleRequest(streamEvent, null);
    }

    private void whenThreeRecordBatchIsProcessed() {
        streamEvent = event(
                record("MODIFY", "stream-1", eventImage(), null),
                record("MODIFY", "stream-2", eventImage(), null),
                record("MODIFY", "stream-3", eventImage(), null));
        response = handler.handleRequest(streamEvent, null);
    }

    private void thenExpectUpsert(String deduplicationId) {
        assertThat(publisher.messages).singleElement().satisfies(message -> {
            assertThat(message.deduplicationId()).isEqualTo(deduplicationId);
            assertThat(message.action()).isInstanceOf(EventSearchProjection.class);
            EventSearchProjection projection = (EventSearchProjection) message.action();
            assertThat(projection.eventId()).isEqualTo("event-1");
            assertThat(projection.venue()).isEqualTo("SoFi Stadium");
            assertThat(projection.city()).isEqualTo("Los Angeles");
        });
        assertThat(failureIds()).isEmpty();
    }

    private void thenExpectDelete(String deduplicationId) {
        assertThat(publisher.messages).containsExactly(
                new Published(new DeleteEventSearchProjection("event-1"), deduplicationId));
        assertThat(failureIds()).isEmpty();
    }

    private void thenExpectNoProjection() {
        assertThat(publisher.messages).isEmpty();
        assertThat(failureIds()).isEmpty();
    }

    private void thenExpectOnlyPrefixPublishedAndSecondSequenceRetried() {
        assertThat(publisher.messages).hasSize(1);
        assertThat(publisher.messages.getFirst().deduplicationId()).isEqualTo("stream-1");
        assertThat(failureIds()).containsExactly("sequence-stream-2");
    }

    private List<String> failureIds() {
        return response.getBatchItemFailures().stream()
                .map(StreamsEventResponse.BatchItemFailure::getItemIdentifier)
                .toList();
    }

    private static EventSearchProjectionLambdaHandler handler(Event event, CapturingPublisher publisher) {
        BuildEventSearchProjectionHandler projectionHandler = new BuildEventSearchProjectionHandler(
                ignored -> Optional.ofNullable(event),
                ignored -> Optional.of(VENUE));
        return new EventSearchProjectionLambdaHandler(projectionHandler, publisher);
    }

    private static Event event(EventStatus status) {
        return new Event(
                EVENT_ID,
                "Taylor Swift",
                VENUE_ID,
                Instant.parse("2026-10-10T03:00:00Z"),
                "CONCERT",
                status,
                "Opening night");
    }

    private static DynamodbEvent event(DynamodbEvent.DynamodbStreamRecord... records) {
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(records));
        return event;
    }

    private static DynamodbEvent.DynamodbStreamRecord record(
            String eventName,
            String eventId,
            Map<String, AttributeValue> newImage,
            Map<String, AttributeValue> oldImage) {
        StreamRecord streamRecord = new StreamRecord();
        streamRecord.setSequenceNumber("sequence-" + eventId);
        streamRecord.setNewImage(newImage);
        streamRecord.setOldImage(oldImage);
        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName(eventName);
        record.setEventID(eventId);
        record.setDynamodb(streamRecord);
        return record;
    }

    private static Map<String, AttributeValue> eventImage() {
        return Map.of(
                "entityType", string("EVENT"),
                "eventId", string("event-1"));
    }

    private static Map<String, AttributeValue> venueImage() {
        return Map.of(
                "entityType", string("VENUE"),
                "venueId", string("venue-1"));
    }

    private static AttributeValue string(String value) {
        return new AttributeValue().withS(value);
    }

    private static final class CapturingPublisher implements EventSearchProjectionPublisher {
        private final List<Published> messages = new ArrayList<>();
        private String failDeduplicationId;

        @Override
        public void publish(EventSearchProjectionAction action, String deduplicationId) {
            if (deduplicationId.equals(failDeduplicationId)) {
                throw new IllegalStateException("simulated SQS publish failure");
            }
            messages.add(new Published(action, deduplicationId));
        }
    }

    private record Published(EventSearchProjectionAction action, String deduplicationId) {
    }
}
