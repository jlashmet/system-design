package com.systemdesign.ticketmaster.booking.bootstrap;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.systemdesign.ticketmaster.booking.application.ProjectSeatMapHandler;
import com.systemdesign.ticketmaster.booking.infrastructure.input.DynamoSeatInventoryStreamProjector;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoSeatMapRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;

/**
 * AWS Lambda entry point for the authoritative inventory DynamoDB Stream.
 *
 * <p>The Lambda event-source mapping owns shard checkpoints, retries, and parallelism and should
 * enable {@code ReportBatchItemFailures}. If one record cannot be projected, the handler returns
 * that record's sequence number immediately so Lambda checkpoints the successful prefix and retries
 * from the failed record forward. Seat-map writes remain idempotent for unavoidable replay.</p>
 */
public final class SeatMapProjectionLambdaHandler implements RequestHandler<DynamodbEvent, StreamsEventResponse> {
    private static final String TABLE_ENV = "TICKETMASTER_SEAT_MAP_TABLE_NAME";

    private final DynamoSeatInventoryStreamProjector projector;

    public SeatMapProjectionLambdaHandler() {
        this(defaultProjector());
    }

    SeatMapProjectionLambdaHandler(DynamoSeatInventoryStreamProjector projector) {
        this.projector = Objects.requireNonNull(projector, "projector");
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
                projector.project(toSdkRecord(record.getDynamodb()));
            } catch (RuntimeException failure) {
                logFailure(context, sequenceNumber, failure);
                return new StreamsEventResponse(List.of(
                        new StreamsEventResponse.BatchItemFailure(sequenceNumber)));
            }
        }
        return new StreamsEventResponse(List.of());
    }

    private static void logFailure(Context context, String sequenceNumber, RuntimeException failure) {
        if (context == null || context.getLogger() == null) return;
        context.getLogger().log("Seat-map projection failed at DynamoDB sequence " + sequenceNumber
                + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
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
        copyNumber(source, newImage, "holdExpiresAt");
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

    private static void copyNumber(
            Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> source,
            Map<String, AttributeValue> target,
            String name) {
        com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue value = source.get(name);
        if (value != null && value.getN() != null) {
            target.put(name, AttributeValue.builder().n(value.getN()).build());
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
