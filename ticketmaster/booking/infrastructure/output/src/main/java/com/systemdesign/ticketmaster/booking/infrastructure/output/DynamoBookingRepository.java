package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.PK;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.RECONCILIATION_INDEX;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.bookingFromItem;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.string;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public final class DynamoBookingRepository implements BookingRepository {
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoBookingRepository(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
    }

    @Override
    public Optional<Booking> findById(BookingId bookingId) {
        Objects.requireNonNull(bookingId, "bookingId");
        return getBooking(DynamoKeys.bookingPk(bookingId));
    }

    @Override
    public Optional<Booking> findByCheckoutIdempotencyKey(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Map<String, AttributeValue> mapping = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of(PK, string(DynamoKeys.idempotencyPk(idempotencyKey))))
                        .consistentRead(true)
                        .build())
                .item();
        if (mapping == null || mapping.isEmpty()) return Optional.empty();
        return findById(new BookingId(mapping.get("bookingId").s()));
    }

    @Override
    public void savePaymentIntent(Booking booking) {
        Objects.requireNonNull(booking, "booking");
        String intentId = booking.paymentIntentIdOptional()
                .orElseThrow(() -> new IllegalArgumentException("booking has no payment intent"));
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.bookingPk(booking.id()))))
                .updateExpression("SET #paymentIntentId = :intent")
                .conditionExpression("#status = :pending AND (attribute_not_exists(#paymentIntentId) OR #paymentIntentId = :intent)")
                .expressionAttributeNames(Map.of(
                        "#status", "status",
                        "#paymentIntentId", "paymentIntentId"))
                .expressionAttributeValues(Map.of(
                        ":pending", string("PENDING_PAYMENT"),
                        ":intent", string(intentId)))
                .build());
    }

    @Override
    public void rescheduleReconciliation(Booking booking) {
        Objects.requireNonNull(booking, "booking");
        if (booking.nextReconcileAt() == null || booking.reconcileShard() == null) {
            throw new IllegalArgumentException("booking is not scheduled for reconciliation");
        }
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.bookingPk(booking.id()))))
                .updateExpression("SET #nextReconcileAt = :next")
                .conditionExpression("#status = :pending AND #reconcileShard = :shard AND #nextReconcileAt < :next")
                .expressionAttributeNames(Map.of(
                        "#status", "status",
                        "#reconcileShard", "reconcileShard",
                        "#nextReconcileAt", "nextReconcileAt"))
                .expressionAttributeValues(Map.of(
                        ":pending", string("PENDING_PAYMENT"),
                        ":shard", string(DynamoKeys.reconciliationShard(booking.reconcileShard())),
                        ":next", DynamoItemCodec.number(booking.nextReconcileAt().toEpochMilli())))
                .build());
    }

    @Override
    public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) {
        Objects.requireNonNull(dueAtOrBefore, "dueAtOrBefore");
        if (shard < 0) throw new IllegalArgumentException("shard must not be negative");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return dynamoDb.query(QueryRequest.builder()
                        .tableName(tableName)
                        .indexName(RECONCILIATION_INDEX)
                        .keyConditionExpression("#shard = :shard AND #due <= :due")
                        .expressionAttributeNames(Map.of(
                                "#shard", "reconcileShard",
                                "#due", "nextReconcileAt"))
                        .expressionAttributeValues(Map.of(
                                ":shard", string(DynamoKeys.reconciliationShard(shard)),
                                ":due", DynamoItemCodec.number(dueAtOrBefore.toEpochMilli())))
                        .scanIndexForward(true)
                        .limit(limit)
                        .build())
                .items().stream()
                .map(DynamoItemCodec::bookingFromItem)
                .toList();
    }

    private Optional<Booking> getBooking(String pk) {
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of(PK, string(pk)))
                        .consistentRead(true)
                        .build())
                .item();
        if (item == null || item.isEmpty()) return Optional.empty();
        return Optional.of(bookingFromItem(item));
    }
}
