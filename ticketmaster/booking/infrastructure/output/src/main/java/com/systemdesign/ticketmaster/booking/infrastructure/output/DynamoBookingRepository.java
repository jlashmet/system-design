package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.PK;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.RECONCILIATION_INDEX;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.bookingFromItem;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.string;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
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
    public Optional<Booking> findByCheckoutIdempotencyKey(
            EventId eventId, HoldId holdId, String idempotencyKey) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Map<String, AttributeValue> mapping = DynamoBookingCall.execute(
                        "checkout idempotency lookup",
                        () -> dynamoDb.getItem(GetItemRequest.builder()
                                .tableName(tableName)
                                .key(Map.of(PK, string(DynamoKeys.idempotencyPk(eventId, holdId, idempotencyKey))))
                                .consistentRead(true)
                                .build()))
                .item();
        if (mapping == null || mapping.isEmpty()) return Optional.empty();

        requireMappingValue(mapping, "eventId", eventId.value());
        requireMappingValue(mapping, "holdId", holdId.value());
        requireMappingValue(mapping, "idempotencyKey", idempotencyKey);
        AttributeValue bookingIdValue = mapping.get("bookingId");
        if (bookingIdValue == null || bookingIdValue.s() == null || bookingIdValue.s().isBlank()) {
            throw new IllegalStateException("checkout idempotency record is missing bookingId");
        }

        BookingId mappedBookingId = new BookingId(bookingIdValue.s());
        Booking booking = findById(mappedBookingId)
                .orElseThrow(() -> new IllegalStateException(
                        "checkout idempotency record references missing booking " + bookingIdValue.s()));
        if (!booking.id().equals(mappedBookingId)
                || !booking.eventId().equals(eventId)
                || !booking.holdId().equals(holdId)
                || !booking.checkoutIdempotencyKey().equals(idempotencyKey)) {
            throw new IllegalStateException("checkout idempotency record resolved outside its booking/event/hold/key scope");
        }
        return Optional.of(booking);
    }

    @Override
    public void savePaymentIntent(Booking booking) {
        Objects.requireNonNull(booking, "booking");
        String intentId = booking.paymentIntentIdOptional()
                .orElseThrow(() -> new IllegalArgumentException("booking has no payment intent"));
        try {
            DynamoBookingCall.execute("save payment intent", () -> dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(PK, string(DynamoKeys.bookingPk(booking.id()))))
                    .updateExpression("SET #paymentIntentId = :intent")
                    .conditionExpression("#eventId = :eventId AND #holdId = :holdId AND #userId = :userId "
                            + "AND #checkoutIdempotencyKey = :checkoutIdempotencyKey "
                            + "AND #totalPriceAmount = :totalPriceAmount AND #totalPriceCurrency = :totalPriceCurrency "
                            + "AND ((#status = :pending AND attribute_not_exists(#paymentIntentId)) "
                            + "OR #paymentIntentId = :intent)")
                    .expressionAttributeNames(Map.of(
                            "#status", "status",
                            "#paymentIntentId", "paymentIntentId",
                            "#eventId", "eventId",
                            "#holdId", "holdId",
                            "#userId", "userId",
                            "#checkoutIdempotencyKey", "checkoutIdempotencyKey",
                            "#totalPriceAmount", "totalPriceAmount",
                            "#totalPriceCurrency", "totalPriceCurrency"))
                    .expressionAttributeValues(Map.of(
                            ":pending", string("PENDING_PAYMENT"),
                            ":intent", string(intentId),
                            ":eventId", string(booking.eventId().value()),
                            ":holdId", string(booking.holdId().value()),
                            ":userId", string(booking.userId().value()),
                            ":checkoutIdempotencyKey", string(booking.checkoutIdempotencyKey()),
                            ":totalPriceAmount", string(booking.totalPrice().amount().toPlainString()),
                            ":totalPriceCurrency", string(booking.totalPrice().currency().getCurrencyCode())))
                    .build()));
        } catch (ConditionalCheckFailedException conflictingIntent) {
            throw new IllegalStateException(
                    "booking payment intent state changed or scope mismatched: " + booking.id().value(),
                    conflictingIntent);
        }
    }

    @Override
    public void rescheduleReconciliation(Booking booking) {
        Objects.requireNonNull(booking, "booking");
        if (booking.nextReconcileAt() == null || booking.reconcileShard() == null) {
            throw new IllegalArgumentException("booking is not scheduled for reconciliation");
        }
        try {
            DynamoBookingCall.execute("reschedule payment reconciliation", () -> dynamoDb.updateItem(UpdateItemRequest.builder()
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
                    .build()));
        } catch (ConditionalCheckFailedException alreadyAdvanced) {
            // Another reconciler may have scheduled a later attempt or finalized the Booking.
            // Both outcomes are strictly further along than this stale retry request.
        }
    }

    @Override
    public List<Booking> findDueForReconciliation(int shard, Instant dueAtOrBefore, int limit) {
        Objects.requireNonNull(dueAtOrBefore, "dueAtOrBefore");
        if (shard < 0) throw new IllegalArgumentException("shard must not be negative");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return DynamoBookingCall.execute("query due payment reconciliations", () -> dynamoDb.query(QueryRequest.builder()
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
                        .build()))
                .items().stream()
                .map(DynamoItemCodec::bookingFromItem)
                .toList();
    }

    private Optional<Booking> getBooking(String pk) {
        Map<String, AttributeValue> item = DynamoBookingCall.execute(
                        "booking lookup",
                        () -> dynamoDb.getItem(GetItemRequest.builder()
                                .tableName(tableName)
                                .key(Map.of(PK, string(pk)))
                                .consistentRead(true)
                                .build()))
                .item();
        if (item == null || item.isEmpty()) return Optional.empty();
        return Optional.of(bookingFromItem(item));
    }

    private static void requireMappingValue(Map<String, AttributeValue> mapping, String name, String expected) {
        AttributeValue value = mapping.get(name);
        if (value == null || value.s() == null || !expected.equals(value.s())) {
            throw new IllegalStateException("checkout idempotency record has inconsistent " + name);
        }
    }
}
