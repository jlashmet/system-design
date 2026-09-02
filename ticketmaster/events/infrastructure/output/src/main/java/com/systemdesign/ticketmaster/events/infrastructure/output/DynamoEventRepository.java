package com.systemdesign.ticketmaster.events.infrastructure.output;

import com.systemdesign.ticketmaster.events.domain.Event;
import com.systemdesign.ticketmaster.events.domain.EventAlreadyExistsException;
import com.systemdesign.ticketmaster.events.domain.EventId;
import com.systemdesign.ticketmaster.events.domain.EventRepository;
import com.systemdesign.ticketmaster.events.domain.EventStatus;
import com.systemdesign.ticketmaster.events.domain.EventWriter;
import com.systemdesign.ticketmaster.events.domain.VenueId;
import com.systemdesign.ticketmaster.events.infrastructure.common.EventsStorageUnavailableException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public final class DynamoEventRepository implements EventRepository, EventWriter {
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
    public void create(Event event) {
        Objects.requireNonNull(event, "event");
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(toItem(event))
                    .conditionExpression("attribute_not_exists(#pk)")
                    .expressionAttributeNames(Map.of("#pk", PK))
                    .build());
        } catch (ConditionalCheckFailedException duplicate) {
            throw new EventAlreadyExistsException(event.id());
        } catch (DynamoDbException | SdkClientException unavailable) {
            throw new EventsStorageUnavailableException("event metadata create", unavailable);
        }
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
        return Optional.of(fromItem(eventId, item));
    }

    static String eventPk(EventId eventId) {
        return "EVENT#" + eventId.value();
    }

    private static Map<String, AttributeValue> toItem(Event event) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, string(eventPk(event.id())));
        item.put("entityType", string("EVENT"));
        item.put("eventId", string(event.id().value()));
        item.put("name", string(event.name()));
        item.put("venueId", string(event.venueId().value()));
        item.put("startsAt", AttributeValue.builder().n(Long.toString(event.startsAt().toEpochMilli())).build());
        item.put("category", string(event.category()));
        item.put("status", string(event.status().name()));
        if (!event.description().isEmpty()) item.put("description", string(event.description()));
        return item;
    }

    private static Event fromItem(EventId eventId, Map<String, AttributeValue> item) {
        return new Event(
                eventId,
                item.get("name").s(),
                new VenueId(item.get("venueId").s()),
                Instant.ofEpochMilli(Long.parseLong(item.get("startsAt").n())),
                item.get("category").s(),
                EventStatus.valueOf(item.get("status").s()),
                item.containsKey("description") ? item.get("description").s() : "");
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
