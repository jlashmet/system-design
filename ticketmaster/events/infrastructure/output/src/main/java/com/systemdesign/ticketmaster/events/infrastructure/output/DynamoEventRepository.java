package com.systemdesign.ticketmaster.events.infrastructure.output;

import com.systemdesign.ticketmaster.events.domain.Event;
import com.systemdesign.ticketmaster.events.domain.EventId;
import com.systemdesign.ticketmaster.events.domain.EventRepository;
import com.systemdesign.ticketmaster.events.domain.EventStatus;
import com.systemdesign.ticketmaster.events.domain.VenueId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

public final class DynamoEventRepository implements EventRepository {
    private static final String PK = "pk";
    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final boolean consistentRead;

    public DynamoEventRepository(DynamoDbClient dynamoDb, String tableName) {
        this(dynamoDb, tableName, false);
    }

    public DynamoEventRepository(DynamoDbClient dynamoDb, String tableName, boolean consistentRead) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.consistentRead = consistentRead;
    }

    @Override
    public Optional<Event> findById(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        Map<String, AttributeValue> item = DynamoEventsCall.execute(
                        "event metadata read",
                        () -> dynamoDb.getItem(GetItemRequest.builder()
                                .tableName(tableName)
                                .key(Map.of(PK, string(eventPk(eventId))))
                                .consistentRead(consistentRead)
                                .build()))
                .item();
        if (item == null || item.isEmpty()) return Optional.empty();
        String expectedPk = eventPk(eventId);
        String storedPk = item.get(PK) == null ? null : item.get(PK).s();
        String storedEventId = item.get("eventId") == null ? null : item.get("eventId").s();
        if (!expectedPk.equals(storedPk) || !eventId.value().equals(storedEventId)) {
            throw new IllegalStateException("event metadata identity mismatch for " + eventId.value());
        }
        return Optional.of(new Event(
                eventId,
                item.get("name").s(),
                new VenueId(item.get("venueId").s()),
                Instant.ofEpochMilli(Long.parseLong(item.get("startsAt").n())),
                item.get("category").s(),
                EventStatus.valueOf(item.get("status").s()),
                item.containsKey("description") ? item.get("description").s() : ""));
    }

    static String eventPk(EventId eventId) {
        return "EVENT#" + eventId.value();
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
