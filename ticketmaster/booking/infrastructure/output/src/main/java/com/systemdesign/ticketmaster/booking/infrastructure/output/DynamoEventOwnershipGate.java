package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.BookingRegionMismatchException;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventOwnershipGate;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

/**
 * Booking-side anti-corruption adapter over the control-plane ownership table.
 * It intentionally knows only the persisted ownership schema, not ControlPlane Java types.
 */
public final class DynamoEventOwnershipGate implements EventOwnershipGate {
    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final String localRegion;

    public DynamoEventOwnershipGate(DynamoDbClient dynamoDb, String tableName, String localRegion) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = requireText(tableName, "tableName");
        this.localRegion = requireText(localRegion, "localRegion");
    }

    @Override
    public void requireLocalOwnership(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", string("EVENT#" + eventId.value())))
                        .consistentRead(true)
                        .build())
                .item();
        if (item == null || item.isEmpty()) {
            throw new BookingRegionMismatchException(eventId, localRegion);
        }

        String ownerRegion = requiredString(item, "ownerRegion");
        long epoch = Long.parseLong(requiredNumber(item, "epoch"));
        if (!localRegion.equals(ownerRegion)) {
            throw new BookingRegionMismatchException(eventId, localRegion, ownerRegion, epoch);
        }
    }

    private static String requiredString(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.s() == null || value.s().isBlank()) {
            throw new IllegalStateException("ownership item is missing " + name);
        }
        return value.s();
    }

    private static String requiredNumber(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.n() == null || value.n().isBlank()) {
            throw new IllegalStateException("ownership item is missing " + name);
        }
        return value.n();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
