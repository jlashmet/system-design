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
    private static final String WAITING_ROOM_ENTRY = "WAITING_ROOM_ENTRY";
    private static final String EVENT_ADMISSION = "EVENT_ADMISSION";
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
            DynamoBookingCall.execute("waiting-room join", () -> dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            PK, string(entryPk(entry.eventId(), entry.userId())),
                            "entityType", string(WAITING_ROOM_ENTRY),
                            "eventId", string(entry.eventId().value()),
                            "userId", string(entry.userId().value()),
                            "joinedAt", number(entry.joinedAt().toEpochMilli())))
                    .conditionExpression("attribute_not_exists(#pk)")
                    .expressionAttributeNames(Map.of("#pk", PK))
                    .build()));
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
        return Optional.of(entryFromItem(item, eventId, userId));
    }

    @Override
    public Optional<EventAdmission> findAdmission(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        Map<String, AttributeValue> item = get(admissionPk(eventId));
        if (item.isEmpty()) return Optional.empty();
        return Optional.of(admissionFromItem(item, eventId));
    }

    @Override
    public EventAdmission initializeAdmission(EventAdmission initial) {
        Objects.requireNonNull(initial, "initial");
        try {
            DynamoBookingCall.execute("admission initialization", () -> dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            PK, string(admissionPk(initial.eventId())),
                            "entityType", string(EVENT_ADMISSION),
                            "eventId", string(initial.eventId().value()),
                            "admittedThrough", number(initial.admittedThrough().toEpochMilli())))
                    .conditionExpression("attribute_not_exists(#pk)")
                    .expressionAttributeNames(Map.of("#pk", PK))
                    .build()));
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
            Map<String, AttributeValue> updated = DynamoBookingCall.execute(
                    "admission watermark advance",
                    () -> dynamoDb.updateItem(UpdateItemRequest.builder()
                            .tableName(tableName)
                            .key(Map.of(PK, string(admissionPk(admission.eventId()))))
                            .updateExpression("SET #admittedThrough = :newWatermark")
                            .conditionExpression(
                                    "attribute_exists(#pk) AND #entityType = :entityType AND #eventId = :eventId "
                                            + "AND #admittedThrough <= :newWatermark")
                            .expressionAttributeNames(Map.of(
                                    "#pk", PK,
                                    "#entityType", "entityType",
                                    "#eventId", "eventId",
                                    "#admittedThrough", "admittedThrough"))
                            .expressionAttributeValues(Map.of(
                                    ":entityType", string(EVENT_ADMISSION),
                                    ":eventId", string(admission.eventId().value()),
                                    ":newWatermark", number(admission.admittedThrough().toEpochMilli())))
                            .returnValues(ReturnValue.ALL_NEW)
                            .build()))
                    .attributes();
            return admissionFromItem(updated, admission.eventId());
        } catch (ConditionalCheckFailedException regressionDisabledOrCorrupt) {
            // The conditional write failed atomically. A consistent read distinguishes a corrupt
            // keyed record (which findAdmission rejects) from the existing disabled/backward case.
            findAdmission(admission.eventId());
            throw new AdmissionWatermarkRegressionException(admission.eventId());
        }
    }

    private WaitingRoomEntry entryFromItem(
            Map<String, AttributeValue> item, EventId expectedEventId, UserId expectedUserId) {
        if (!entryPk(expectedEventId, expectedUserId).equals(stringValue(item.get(PK)))
                || !WAITING_ROOM_ENTRY.equals(stringValue(item.get("entityType")))
                || !expectedEventId.value().equals(stringValue(item.get("eventId")))
                || !expectedUserId.value().equals(stringValue(item.get("userId")))) {
            throw new IllegalStateException(
                    "waiting-room entry identity mismatch for "
                            + expectedEventId.value() + "/" + expectedUserId.value());
        }
        AttributeValue joinedAt = item.get("joinedAt");
        if (joinedAt == null || joinedAt.n() == null) {
            throw new IllegalStateException(
                    "waiting-room entry is incomplete for "
                            + expectedEventId.value() + "/" + expectedUserId.value());
        }
        return new WaitingRoomEntry(
                expectedEventId,
                expectedUserId,
                Instant.ofEpochMilli(Long.parseLong(joinedAt.n())));
    }

    private EventAdmission admissionFromItem(Map<String, AttributeValue> item, EventId expectedEventId) {
        if (!admissionPk(expectedEventId).equals(stringValue(item.get(PK)))
                || !EVENT_ADMISSION.equals(stringValue(item.get("entityType")))
                || !expectedEventId.value().equals(stringValue(item.get("eventId")))) {
            throw new IllegalStateException("admission record identity mismatch for event " + expectedEventId.value());
        }
        AttributeValue admittedThrough = item.get("admittedThrough");
        if (admittedThrough == null || admittedThrough.n() == null) {
            throw new IllegalStateException("admission record is incomplete for event " + expectedEventId.value());
        }
        return new EventAdmission(
                expectedEventId,
                Instant.ofEpochMilli(Long.parseLong(admittedThrough.n())));
    }

    private Map<String, AttributeValue> get(String pk) {
        Map<String, AttributeValue> item = DynamoBookingCall.execute(
                        "waiting-room state read",
                        () -> dynamoDb.getItem(GetItemRequest.builder()
                                .tableName(tableName)
                                .key(Map.of(PK, string(pk)))
                                .consistentRead(true)
                                .build()))
                .item();
        return item == null ? Map.of() : item;
    }

    static String entryPk(EventId eventId, UserId userId) {
        return "WAIT#EVENT#" + eventId.value() + "#USER#" + userId.value();
    }

    static String admissionPk(EventId eventId) {
        return "ADMISSION#EVENT#" + eventId.value();
    }

    private static String stringValue(AttributeValue value) {
        return value == null ? null : value.s();
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
