package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventOwnershipUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

final class DynamoReservationEventFence {
    private static final String PK = "pk";

    private DynamoReservationEventFence() {}

    static Fence resolve(DynamoDbClient dynamoDb, String tableName, EventId eventId, String localRegion) {
        Objects.requireNonNull(eventId, "eventId");
        Map<String, AttributeValue> item = DynamoReservationCall.execute(
                "event write fence lookup",
                () -> dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of(PK, string(DynamoReservationKeys.eventOwnershipPk(eventId))))
                        .consistentRead(true)
                        .build())).item();
        if (item == null || item.isEmpty()) {
            throw new EventOwnershipUnavailableException(eventId, "authoritative booking fence is missing");
        }
        String ownerRegion = stringValue(item.get("ownerRegion"));
        String storedEventId = stringValue(item.get("eventId"));
        String entityType = stringValue(item.get("entityType"));
        long epoch = longValue(item.get("epoch"));
        if (!"EVENT_OWNERSHIP".equals(entityType)
                || !eventId.value().equals(storedEventId)
                || ownerRegion == null || ownerRegion.isBlank()
                || epoch < 1) {
            throw new EventOwnershipUnavailableException(eventId, "authoritative booking fence is malformed");
        }
        if (!localRegion.equals(ownerRegion)) {
            throw new WrongBookingRegionException(eventId, localRegion, ownerRegion);
        }
        return new Fence(ownerRegion, epoch);
    }

    static TransactWriteItem condition(String tableName, EventId eventId, Fence fence) {
        return TransactWriteItem.builder()
                .conditionCheck(ConditionCheck.builder()
                        .tableName(tableName)
                        .key(Map.of(PK, string(DynamoReservationKeys.eventOwnershipPk(eventId))))
                        .conditionExpression("#entityType = :type AND #eventId = :eventId AND #ownerRegion = :ownerRegion AND #epoch = :epoch")
                        .expressionAttributeNames(Map.of(
                                "#entityType", "entityType",
                                "#eventId", "eventId",
                                "#ownerRegion", "ownerRegion",
                                "#epoch", "epoch"))
                        .expressionAttributeValues(Map.of(
                                ":type", string("EVENT_OWNERSHIP"),
                                ":eventId", string(eventId.value()),
                                ":ownerRegion", string(fence.ownerRegion()),
                                ":epoch", number(fence.epoch())))
                        .build())
                .build();
    }

    private static String stringValue(AttributeValue value) {
        return value == null ? null : value.s();
    }

    private static long longValue(AttributeValue value) {
        if (value == null || value.n() == null) return -1;
        try {
            return Long.parseLong(value.n());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    record Fence(String ownerRegion, long epoch) {}
}
