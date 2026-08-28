package com.systemdesign.ticketmaster.events.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
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

    @Test
    void publishesUpsertForEventInsertOrModify() {
        CapturingPublisher publisher = new CapturingPublisher();
        EventSearchProjectionLambdaHandler handler = handler(event(EventStatus.SCHEDULED), publisher);

        handler.handleRequest(event("MODIFY", "stream-1", eventImage(), null), null);

        assertThat(publisher.messages).singleElement().satisfies(message -> {
            assertThat(message.deduplicationId()).isEqualTo("stream-1");
            assertThat(message.action()).isInstanceOf(EventSearchProjection.class);
            EventSearchProjection projection = (EventSearchProjection) message.action();
            assertThat(projection.eventId()).isEqualTo("event-1");
            assertThat(projection.venue()).isEqualTo("SoFi Stadium");
            assertThat(projection.city()).isEqualTo("Los Angeles");
        });
    }

    @Test
    void publishesDeleteWhenCanonicalEventIsCancelled() {
        CapturingPublisher publisher = new CapturingPublisher();
        EventSearchProjectionLambdaHandler handler = handler(event(EventStatus.CANCELLED), publisher);

        handler.handleRequest(event("MODIFY", "stream-2", eventImage(), null), null);

        assertThat(publisher.messages).singleElement().satisfies(message -> {
            assertThat(message.deduplicationId()).isEqualTo("stream-2");
            assertThat(message.action()).isEqualTo(new DeleteEventSearchProjection("event-1"));
        });
    }

    @Test
    void publishesDeleteDirectlyForRemovedEventRow() {
        CapturingPublisher publisher = new CapturingPublisher();
        EventSearchProjectionLambdaHandler handler = handler(null, publisher);

        handler.handleRequest(event("REMOVE", "stream-3", null, eventImage()), null);

        assertThat(publisher.messages).containsExactly(
                new Published(new DeleteEventSearchProjection("event-1"), "stream-3"));
    }

    @Test
    void ignoresNonEventRows() {
        CapturingPublisher publisher = new CapturingPublisher();
        EventSearchProjectionLambdaHandler handler = handler(event(EventStatus.SCHEDULED), publisher);
        Map<String, AttributeValue> venueImage = Map.of(
                "entityType", string("VENUE"),
                "venueId", string("venue-1"));

        handler.handleRequest(event("MODIFY", "stream-4", venueImage, null), null);

        assertThat(publisher.messages).isEmpty();
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

    private static DynamodbEvent event(
            String eventName,
            String eventId,
            Map<String, AttributeValue> newImage,
            Map<String, AttributeValue> oldImage) {
        StreamRecord streamRecord = new StreamRecord();
        streamRecord.setNewImage(newImage);
        streamRecord.setOldImage(oldImage);
        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName(eventName);
        record.setEventID(eventId);
        record.setDynamodb(streamRecord);
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record));
        return event;
    }

    private static Map<String, AttributeValue> eventImage() {
        return Map.of(
                "entityType", string("EVENT"),
                "eventId", string("event-1"));
    }

    private static AttributeValue string(String value) {
        return new AttributeValue().withS(value);
    }

    private static final class CapturingPublisher implements EventSearchProjectionPublisher {
        private final List<Published> messages = new ArrayList<>();

        @Override
        public void publish(EventSearchProjectionAction action, String deduplicationId) {
            messages.add(new Published(action, deduplicationId));
        }
    }

    private record Published(EventSearchProjectionAction action, String deduplicationId) {
    }
}
