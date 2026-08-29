package com.systemdesign.ticketmaster.events.bootstrap;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.ticketmaster.events.application.BuildEventSearchProjectionHandler;
import com.systemdesign.ticketmaster.events.application.BuildEventSearchProjectionQuery;
import com.systemdesign.ticketmaster.events.application.DeleteEventSearchProjection;
import com.systemdesign.ticketmaster.events.application.EventSearchProjectionAction;
import com.systemdesign.ticketmaster.events.domain.EventId;
import com.systemdesign.ticketmaster.events.infrastructure.output.DynamoEventRepository;
import com.systemdesign.ticketmaster.events.infrastructure.output.DynamoVenueRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * DynamoDB Stream Lambda that publishes the Events bounded-context search projection to SQS FIFO.
 *
 * <p>Each event ID is used as the FIFO message group, preserving per-event update order. The
 * DynamoDB Stream event ID is used as the SQS deduplication ID. The Lambda event-source mapping
 * should enable {@code ReportBatchItemFailures}; if one stream record fails, this handler returns
 * that record's sequence number immediately so Lambda checkpoints the successful prefix and retries
 * from the failed record forward.</p>
 */
public final class EventSearchProjectionLambdaHandler implements RequestHandler<DynamodbEvent, StreamsEventResponse> {
    private static final String TABLE_ENV = "TICKETMASTER_EVENTS_TABLE_NAME";
    private static final String QUEUE_ENV = "TICKETMASTER_SEARCH_PROJECTION_QUEUE_URL";
    private static final String EVENT_PK_PREFIX = "EVENT#";

    private final BuildEventSearchProjectionHandler projectionHandler;
    private final EventSearchProjectionPublisher publisher;

    public EventSearchProjectionLambdaHandler() {
        this(defaultDependencies());
    }

    private EventSearchProjectionLambdaHandler(Dependencies dependencies) {
        this(dependencies.projectionHandler(), dependencies.publisher());
    }

    EventSearchProjectionLambdaHandler(
            BuildEventSearchProjectionHandler projectionHandler,
            EventSearchProjectionPublisher publisher) {
        this.projectionHandler = Objects.requireNonNull(projectionHandler, "projectionHandler");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public StreamsEventResponse handleRequest(DynamodbEvent event, Context context) {
        Objects.requireNonNull(event, "event");
        if (event.getRecords() == null) return new StreamsEventResponse(List.of());
        for (DynamodbEvent.DynamodbStreamRecord record : event.getRecords()) {
            if (record == null || record.getDynamodb() == null) continue;
            String sequenceNumber = requireNonBlank(
                    record.getDynamodb().getSequenceNumber(), "DynamoDB Stream sequence number");
            try {
                handleRecord(record);
            } catch (RuntimeException failure) {
                logFailure(context, record, failure);
                return new StreamsEventResponse(List.of(
                        new StreamsEventResponse.BatchItemFailure(sequenceNumber)));
            }
        }
        return new StreamsEventResponse(List.of());
    }

    private static void logFailure(
            Context context,
            DynamodbEvent.DynamodbStreamRecord record,
            RuntimeException failure) {
        if (context == null || context.getLogger() == null) return;
        String eventId = record.getEventID() == null ? "<missing>" : record.getEventID();
        context.getLogger().log("Event search projection publish failed for stream event " + eventId
                + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }

    private void handleRecord(DynamodbEvent.DynamodbStreamRecord record) {
        String deduplicationId = requireNonBlank(record.getEventID(), "DynamoDB Stream eventID");
        Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> newImage =
                record.getDynamodb().getNewImage();
        Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> oldImage =
                record.getDynamodb().getOldImage();

        if ("REMOVE".equals(record.getEventName())) {
            String eventId = removedEventId(record.getDynamodb().getKeys(), oldImage);
            if (eventId == null) return;
            publisher.publish(new DeleteEventSearchProjection(eventId), deduplicationId);
            return;
        }

        if (!isEvent(newImage)) return;
        EventId eventId = new EventId(string(newImage, "eventId"));
        EventSearchProjectionAction action = projectionHandler
                .handle(new BuildEventSearchProjectionQuery(eventId))
                .orElseThrow(() -> new IllegalStateException(
                        "stream referenced event that is not readable yet: " + eventId.value()));
        publisher.publish(action, deduplicationId);
    }

    private static String removedEventId(
            Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> keys,
            Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> oldImage) {
        if (isEvent(oldImage)) return string(oldImage, "eventId");
        if (keys == null || keys.isEmpty()) return null;
        com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue pk = keys.get("pk");
        if (pk == null || pk.getS() == null || !pk.getS().startsWith(EVENT_PK_PREFIX)) return null;
        String eventId = pk.getS().substring(EVENT_PK_PREFIX.length());
        return eventId.isBlank() ? null : eventId;
    }

    private static boolean isEvent(
            Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> image) {
        if (image == null || image.isEmpty()) return false;
        com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue entityType = image.get("entityType");
        return entityType != null && "EVENT".equals(entityType.getS());
    }

    private static String string(
            Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> image,
            String name) {
        com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue value = image.get(name);
        return value == null ? null : requireNonBlank(value.getS(), name);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static Dependencies defaultDependencies() {
        String tableName = requireEnv(TABLE_ENV);
        String queueUrl = requireEnv(QUEUE_ENV);
        DynamoDbClient dynamoDb = DynamoDbClient.create();
        BuildEventSearchProjectionHandler handler = new BuildEventSearchProjectionHandler(
                new DynamoEventRepository(dynamoDb, tableName, true),
                new DynamoVenueRepository(dynamoDb, tableName, true));
        EventSearchProjectionPublisher publisher = new SqsEventSearchProjectionPublisher(
                SqsClient.create(), new ObjectMapper(), queueUrl);
        return new Dependencies(handler, publisher);
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured");
        return value;
    }

    private record Dependencies(
            BuildEventSearchProjectionHandler projectionHandler,
            EventSearchProjectionPublisher publisher) {
    }
}
