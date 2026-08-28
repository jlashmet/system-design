package com.systemdesign.ticketmaster.events.bootstrap;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.ticketmaster.events.application.BuildEventSearchProjectionHandler;
import com.systemdesign.ticketmaster.events.application.BuildEventSearchProjectionQuery;
import com.systemdesign.ticketmaster.events.application.DeleteEventSearchProjection;
import com.systemdesign.ticketmaster.events.application.EventSearchProjectionAction;
import com.systemdesign.ticketmaster.events.domain.EventId;
import com.systemdesign.ticketmaster.events.infrastructure.output.DynamoEventRepository;
import com.systemdesign.ticketmaster.events.infrastructure.output.DynamoVenueRepository;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * DynamoDB Stream Lambda that publishes the Events bounded-context search projection to SQS FIFO.
 *
 * <p>Each event ID is used as the FIFO message group, preserving per-event update order. The
 * DynamoDB Stream event ID is used as the SQS deduplication ID, so retrying a stream record does
 * not duplicate the projection message.</p>
 */
public final class EventSearchProjectionLambdaHandler implements RequestHandler<DynamodbEvent, Void> {
    private static final String TABLE_ENV = "TICKETMASTER_EVENTS_TABLE_NAME";
    private static final String QUEUE_ENV = "TICKETMASTER_SEARCH_PROJECTION_QUEUE_URL";

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
    public Void handleRequest(DynamodbEvent event, Context context) {
        Objects.requireNonNull(event, "event");
        if (event.getRecords() == null) return null;
        for (DynamodbEvent.DynamodbStreamRecord record : event.getRecords()) {
            if (record == null || record.getDynamodb() == null) continue;
            handleRecord(record);
        }
        return null;
    }

    private void handleRecord(DynamodbEvent.DynamodbStreamRecord record) {
        String deduplicationId = requireNonBlank(record.getEventID(), "DynamoDB Stream eventID");
        Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> newImage =
                record.getDynamodb().getNewImage();
        Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> oldImage =
                record.getDynamodb().getOldImage();

        if ("REMOVE".equals(record.getEventName())) {
            if (!isEvent(oldImage)) return;
            publisher.publish(new DeleteEventSearchProjection(string(oldImage, "eventId")), deduplicationId);
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
