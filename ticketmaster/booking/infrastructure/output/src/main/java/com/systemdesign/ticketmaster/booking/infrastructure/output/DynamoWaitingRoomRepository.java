package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.AdmissionWatermarkRegressionException;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.time.Instant;
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

public final class DynamoWaitingRoomRepository implements WaitingRoomRepository {
    private static final String PK = "pk";
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoWaitingRoomRepository(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
    }

    @Override
    public WaitingRoomEntry join(WaitingRoomEntry entry) {
        Objects.requireNonNull(entry, "entry");
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            PK, string(entryPk(entry.eventId(), entry.userId())),
                            "entityType", string("WAITING_ROOM_ENTRY"),
                            "eventId", string(entry.eventId().value()),
                            "userId", string(entry.userId().value()),
                            "joinedAt", number(entry.joinedAt().toEpochMilli())))
                    .conditionExpression("attribute_not_exists(#pk)")
                    .expressionAttributeNames(Map.of("#pk", PK))
                    .build());
            return entry;
        } catch (ConditionalCheckFailedException alreadyJoined) {
            return findEntry(entry.eventId(), entry.userId())
                    .orElseThrow(() -> new IllegalStateException("waiting room entry disappeared after concurrent join", alreadyJoined));
        }
    }

    @Override
    public Optional<WaitingRoomEntry> findEntry(EventId eventId, UserId userId) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
        Map<String, AttributeValue> item = get(entryPk(eventId, userId));
        if (item.isEmpty()) return Optional.empty();
        return Optional.of(new WaitingRoomEntry(
                new EventId(item.get("eventId").s()),
                new UserId(item.get("userId").s()),
                Instant.ofEpochMilli(Long.parseLong(item.get("joinedAt").n()))));
    }

    @Override
    public Optional<EventAdmission> findAdmission(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        Map<String, AttributeValue> item = get(admissionPk(eventId));
        if (item.isEmpty()) return Optional.empty();
        AttributeValue storedEventId = item.get("eventId");
        AttributeValue admittedThrough = item.get("admittedThrough");
        if (storedEventId == null || storedEventId.s() == null
                || admittedThrough == null || admittedThrough.n() == null) {
            throw new IllegalStateException("admission record is incomplete for event " + eventId.value());
        }
        return Optional.of(new EventAdmission(
                new EventId(storedEventId.s()),
                Instant.ofEpochMilli(Long.parseLong(admittedThrough.n()))));
    }

    @Override
    public EventAdmission initializeAdmission(EventAdmission initial) {
        Objects.requireNonNull(initial, "initial");
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            PK, string(admissionPk(initial.eventId())),
                            "entityType", string("EVENT_ADMISSION"),
                            "eventId", string(initial.eventId().value()),
                            "admittedThrough", number(initial.admittedThrough().toEpochMilli())))
                    .conditionExpression("attribute_not_exists(#pk)")
                    .expressionAttributeNames(Map.of("#pk", PK))
                    .build());
            return initial;
        } catch (ConditionalCheckFailedException alreadyEnabled) {
            return findAdmission(initial.eventId())
                    .orElseThrow(() -> new IllegalStateException(
                            "admission record disappeared after concurrent initialization",
                            alreadyEnabled));
        }
    }

    @Override
    public EventAdmission advanceAdmission(EventAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        try {
            Map<String, AttributeValue> updated = dynamoDb.updateItem(UpdateItemRequest.builder()
                            .tableName(tableName)
                            .key(Map.of(PK, string(admissionPk(admission.eventId()))))
                            .updateExpression("SET #admittedThrough = :newWatermark")
                            .conditionExpression("attribute_exists(#pk) AND #admittedThrough <= :newWatermark")
                            .expressionAttributeNames(Map.of(
                                    "#pk", PK,
                                    "#admittedThrough", "admittedThrough"))
                            .expressionAttributeValues(Map.of(
                                    ":newWatermark", number(admission.admittedThrough().toEpochMilli())))
                            .returnValues(ReturnValue.ALL_NEW)
                            .build())
                    .attributes();
            return new EventAdmission(
                    new EventId(updated.get("eventId").s()),
                    Instant.ofEpochMilli(Long.parseLong(updated.get("admittedThrough").n())));
        } catch (ConditionalCheckFailedException regressionOrDisabled) {
            throw new AdmissionWatermarkRegressionException(admission.eventId());
        }
    }

    private Map<String, AttributeValue> get(String pk) {
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of(PK, string(pk)))
                        .consistentRead(true)
                        .build())
                .item();
        return item == null ? Map.of() : item;
    }

    static String entryPk(EventId eventId, UserId userId) {
        return "WAIT#EVENT#" + eventId.value() + "#USER#" + userId.value();
    }

    static String admissionPk(EventId eventId) {
        return "ADMISSION#EVENT#" + eventId.value();
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
