package com.systemdesign.ticketmaster.controlplane.infrastructure.output;

import com.systemdesign.ticketmaster.controlplane.domain.EventId;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnership;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnershipRepository;
import com.systemdesign.ticketmaster.controlplane.domain.OwnershipConflictException;
import com.systemdesign.ticketmaster.controlplane.domain.RegionId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public final class DynamoEventOwnershipRepository implements EventOwnershipRepository {
    private static final String PK = "pk";
    private static final String EVENT_ID = "eventId";
    private static final String OWNER_REGION = "ownerRegion";
    private static final String EPOCH = "epoch";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoEventOwnershipRepository(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        if (tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
    }

    @Override
    public Optional<EventOwnership> findByEventId(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        Map<String, AttributeValue> item = readItem(eventId);
        if (item == null || item.isEmpty()) return Optional.empty();
        return Optional.of(toDomain(eventId, item));
    }

    @Override
    public EventOwnership assignIfAbsent(EventOwnership ownership) {
        Objects.requireNonNull(ownership, "ownership");
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(toItem(ownership))
                    .conditionExpression("attribute_not_exists(#pk)")
                    .expressionAttributeNames(Map.of("#pk", PK))
                    .build());
            return ownership;
        } catch (ConditionalCheckFailedException e) {
            throw new OwnershipConflictException(
                    "event " + ownership.eventId().value() + " already has an owner");
        }
    }

    @Override
    public EventOwnership transfer(EventId eventId, RegionId expectedOwner, long expectedEpoch, RegionId newOwner) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(expectedOwner, "expectedOwner");
        Objects.requireNonNull(newOwner, "newOwner");
        long nextEpoch = Math.addExact(expectedEpoch, 1);
        try {
            Map<String, AttributeValue> updated = dynamoDb.updateItem(UpdateItemRequest.builder()
                            .tableName(tableName)
                            .key(Map.of(PK, string(pk(eventId))))
                            .updateExpression("SET #owner = :newOwner, #epoch = :nextEpoch")
                            .conditionExpression(
                                    "#eventId = :eventId AND #owner = :expectedOwner AND #epoch = :expectedEpoch")
                            .expressionAttributeNames(Map.of(
                                    "#eventId", EVENT_ID,
                                    "#owner", OWNER_REGION,
                                    "#epoch", EPOCH))
                            .expressionAttributeValues(Map.of(
                                    ":eventId", string(eventId.value()),
                                    ":newOwner", string(newOwner.value()),
                                    ":nextEpoch", number(nextEpoch),
                                    ":expectedOwner", string(expectedOwner.value()),
                                    ":expectedEpoch", number(expectedEpoch)))
                            .returnValues(ReturnValue.ALL_NEW)
                            .build())
                    .attributes();
            return toDomain(eventId, updated);
        } catch (ConditionalCheckFailedException e) {
            Map<String, AttributeValue> current = readItem(eventId);
            if (current != null && !current.isEmpty()) {
                requireMatchingIdentity(eventId, current);
            }
            throw new OwnershipConflictException(
                    "event " + eventId.value() + " ownership changed before transfer");
        }
    }

    static String pk(EventId eventId) {
        return "EVENT#" + eventId.value();
    }

    private Map<String, AttributeValue> readItem(EventId eventId) {
        return dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of(PK, string(pk(eventId))))
                        .consistentRead(true)
                        .build())
                .item();
    }

    private static Map<String, AttributeValue> toItem(EventOwnership ownership) {
        return Map.of(
                PK, string(pk(ownership.eventId())),
                EVENT_ID, string(ownership.eventId().value()),
                OWNER_REGION, string(ownership.ownerRegion().value()),
                EPOCH, number(ownership.epoch()));
    }

    private static EventOwnership toDomain(EventId expectedEventId, Map<String, AttributeValue> item) {
        requireMatchingIdentity(expectedEventId, item);
        return new EventOwnership(
                expectedEventId,
                new RegionId(item.get(OWNER_REGION).s()),
                Long.parseLong(item.get(EPOCH).n()));
    }

    private static void requireMatchingIdentity(EventId expectedEventId, Map<String, AttributeValue> item) {
        AttributeValue storedPk = item.get(PK);
        AttributeValue storedEventId = item.get(EVENT_ID);
        if (storedPk == null
                || storedPk.s() == null
                || !pk(expectedEventId).equals(storedPk.s())
                || storedEventId == null
                || storedEventId.s() == null
                || !expectedEventId.value().equals(storedEventId.s())) {
            throw new IllegalStateException(
                    "event ownership identity mismatch for " + expectedEventId.value());
        }
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
