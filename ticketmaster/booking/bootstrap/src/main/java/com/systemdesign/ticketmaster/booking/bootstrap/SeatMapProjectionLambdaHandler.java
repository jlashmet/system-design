package com.systemdesign.ticketmaster.booking.bootstrap;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.systemdesign.ticketmaster.booking.application.ProjectSeatMapHandler;
import com.systemdesign.ticketmaster.booking.infrastructure.input.DynamoSeatInventoryStreamProjector;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoSeatMapRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;

/**
 * AWS Lambda entry point for the authoritative inventory DynamoDB Stream.
 *
 * <p>The Lambda event-source mapping owns shard checkpoints, retries, and parallelism. This handler
 * only adapts the AWS Lambda event model to the Booking input projector. Seat-map writes are
 * idempotent, so retrying a stream batch safely converges on the latest image.</p>
 */
public final class SeatMapProjectionLambdaHandler implements RequestHandler<DynamodbEvent, Void> {
    private static final String TABLE_ENV = "TICKETMASTER_SEAT_MAP_TABLE_NAME";

    private final DynamoSeatInventoryStreamProjector projector;

    public SeatMapProjectionLambdaHandler() {
        this(defaultProjector());
    }

    SeatMapProjectionLambdaHandler(DynamoSeatInventoryStreamProjector projector) {
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    @Override
    public Void handleRequest(DynamodbEvent event, Context context) {
        Objects.requireNonNull(event, "event");
        if (event.getRecords() == null) return null;
        for (DynamodbEvent.DynamodbStreamRecord record : event.getRecords()) {
            if (record == null || record.getDynamodb() == null) continue;
            projector.project(toSdkRecord(record.getDynamodb()));
        }
        return null;
    }

    private static DynamoSeatInventoryStreamProjector defaultProjector() {
        String tableName = System.getenv(TABLE_ENV);
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalStateException(TABLE_ENV + " must be configured for the seat-map projection Lambda");
        }
        DynamoDbClient dynamoDb = DynamoDbClient.create();
        return new DynamoSeatInventoryStreamProjector(
                new ProjectSeatMapHandler(new DynamoSeatMapRepository(dynamoDb, tableName)));
    }

    private static StreamRecord toSdkRecord(
            com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord record) {
        Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> source =
                record.getNewImage();
        if (source == null || source.isEmpty()) return StreamRecord.builder().build();

        Map<String, AttributeValue> newImage = new HashMap<>();
        copyString(source, newImage, "entityType");
        copyString(source, newImage, "eventId");
        copyString(source, newImage, "sectionId");
        copyString(source, newImage, "seatId");
        copyString(source, newImage, "row");
        copyString(source, newImage, "number");
        copyString(source, newImage, "priceAmount");
        copyString(source, newImage, "priceCurrency");
        copyString(source, newImage, "status");
        return StreamRecord.builder().newImage(newImage).build();
    }

    private static void copyString(
            Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> source,
            Map<String, AttributeValue> target,
            String name) {
        com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue value = source.get(name);
        if (value != null && value.getS() != null) {
            target.put(name, AttributeValue.builder().s(value.getS()).build());
        }
    }
}
