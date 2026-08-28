package com.systemdesign.ticketmaster.events.infrastructure.output;

import com.systemdesign.ticketmaster.events.domain.Venue;
import com.systemdesign.ticketmaster.events.domain.VenueId;
import com.systemdesign.ticketmaster.events.domain.VenueRepository;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

public final class DynamoVenueRepository implements VenueRepository {
    private static final String PK = "pk";
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoVenueRepository(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
    }

    @Override
    public Optional<Venue> findById(VenueId venueId) {
        Objects.requireNonNull(venueId, "venueId");
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of(PK, string(venuePk(venueId))))
                        .consistentRead(true)
                        .build())
                .item();
        if (item == null || item.isEmpty()) return Optional.empty();
        return Optional.of(new Venue(
                new VenueId(item.get("venueId").s()),
                item.get("name").s(),
                item.get("city").s()));
    }

    static String venuePk(VenueId venueId) {
        return "VENUE#" + venueId.value();
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
