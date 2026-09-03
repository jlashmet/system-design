package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.PK;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.RECONCILIATION_INDEX;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.STATE;
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
        Map<String, AttributeValue> item = DynamoBookingCall.execute(
                        "booking lookup",
                        () -> dynamoDb.getItem(GetItemRequest.builder()
                                .tableName(tableName)
                                .key(Map.of(PK, string(DynamoKeys.bookingPk(bookingId))))
                                .consistentRead(true)
                                .build()))
                .item();
        if (item == null || item.isEmpty()) return Optional.empty();

        return Optional.of(requireBookingIdentity(item, bookingFromItem(item), bookingId));
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
        if (!booking.eventId().equals(eventId)
                || !booking.holdId().equals(holdId)
                || !booking.checkoutIdempotencyKey().equals(idempotencyKey)) {
            throw new IllegalStateException("checkout idempotency record resolved outside its booking/event/hold/key scope");
        }
        return Optional.of(booking);
    }

    @Override
    public void savePaymentIntent(Booking booking) {
        Objects.requireNonNull(booking, "booking");
        if (!(booking.state() instanceof Booking.PaymentPending pending)) {
            throw new IllegalArgumentException("booking is not payment-pending");
        }
        String intentId = pending.paymentIntentId();
        try {
            DynamoBookingCall.execute("save payment intent", () -> dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(PK, string(DynamoKeys.bookingPk(booking.id()))))
                    .updateExpression("SET #state = :state, #reconcileShard = :gsiShard, #nextReconcileAt = :next "
                            + "REMOVE #status, #paymentIntentId")
                    .conditionExpression("#eventId = :eventId AND #holdId = :holdId AND #userId = :userId "
                            + "AND #checkoutIdempotencyKey = :checkoutIdempotencyKey "
                            + "AND #totalPriceAmount = :totalPriceAmount AND #totalPriceCurrency = :totalPriceCurrency "
                            + "AND ((attribute_exists(#state) AND "
                            + "((#state.#stateType = :intentPending) OR "
                            + "(#state.#stateType = :paymentPending AND #state.#stateIntent = :intent))) "
                            + "OR (attribute_not_exists(#state) AND #status = :legacyPending "
                            + "AND (attribute_not_exists(#paymentIntentId) OR #paymentIntentId = :intent)))")
                    .expressionAttributeNames(Map.ofEntries(
                            Map.entry("#state", STATE),
                            Map.entry("#stateType", "type"),
                            Map.entry("#stateIntent", "paymentIntentId"),
                            Map.entry("#status", "status"),
                            Map.entry("#paymentIntentId", "paymentIntentId"),
                            Map.entry("#reconcileShard", "reconcileShard"),
                            Map.entry("#nextReconcileAt", "nextReconcileAt"),
                            Map.entry("#eventId", "eventId"),
                            Map.entry("#holdId", "holdId"),
                            Map.entry("#userId", "userId"),
                            Map.entry("#checkoutIdempotencyKey", "checkoutIdempotencyKey"),
                            Map.entry("#totalPriceAmount", "totalPriceAmount"),
                            Map.entry("#totalPriceCurrency", "totalPriceCurrency")))
                    .expressionAttributeValues(Map.ofEntries(
                            Map.entry(":state", DynamoItemCodec.bookingState(booking.state())),
                            Map.entry(":intentPending", string("PAYMENT_INTENT_PENDING")),
                            Map.entry(":paymentPending", string("PAYMENT_PENDING")),
                            Map.entry(":legacyPending", string("PENDING_PAYMENT")),
                            Map.entry(":intent", string(intentId)),
                            Map.entry(":gsiShard", string(DynamoKeys.reconciliationShard(pending.reconcileShard()))),
                            Map.entry(":next", DynamoItemCodec.number(pending.nextReconcileAt().toEpochMilli())),
                            Map.entry(":eventId", string(booking.eventId().value())),
                            Map.entry(":holdId", string(booking.holdId().value())),
                            Map.entry(":userId", string(booking.userId().value())),
                            Map.entry(":checkoutIdempotencyKey", string(booking.checkoutIdempotencyKey())),
                            Map.entry(":totalPriceAmount", string(booking.totalPrice().amount().toPlainString())),
                            Map.entry(":totalPriceCurrency", string(booking.totalPrice().currency().getCurrencyCode()))))
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
        String expectedStateType;
        Map<String, AttributeValue> values = new java.util.HashMap<>();
        if (booking.state() instanceof Booking.PaymentIntentPending) {
            expectedStateType = "PAYMENT_INTENT_PENDING";
        } else if (booking.state() instanceof Booking.PaymentPending pending) {
            expectedStateType = "PAYMENT_PENDING";
            values.put(":intent", string(pending.paymentIntentId()));
        } else {
            throw new IllegalArgumentException("booking is not pending payment");
        }
        values.put(":state", DynamoItemCodec.bookingState(booking.state()));
        values.put(":stateType", string(expectedStateType));
        values.put(":legacyPending", string("PENDING_PAYMENT"));
        values.put(":shard", string(DynamoKeys.reconciliationShard(booking.reconcileShard())));
        values.put(":stateShard", DynamoItemCodec.number(booking.reconcileShard()));
        values.put(":next", DynamoItemCodec.number(booking.nextReconcileAt().toEpochMilli()));

        String typedIntentCondition = booking.state() instanceof Booking.PaymentPending
                ? " AND #state.#stateIntent = :intent"
                : " AND attribute_not_exists(#state.#stateIntent)";
        String legacyIntentCondition = booking.state() instanceof Booking.PaymentPending
                ? " AND #paymentIntentId = :intent"
                : " AND attribute_not_exists(#paymentIntentId)";
        try {
            DynamoBookingCall.execute("reschedule payment reconciliation", () -> dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(PK, string(DynamoKeys.bookingPk(booking.id()))))
                    .updateExpression("SET #state = :state, #reconcileShard = :shard, #nextReconcileAt = :next "
                            + "REMOVE #status, #paymentIntentId")
                    .conditionExpression("((attribute_exists(#state) AND #state.#stateType = :stateType "
                            + "AND #state.#stateShard = :stateShard AND #state.#stateNext < :next"
                            + typedIntentCondition + ") OR "
                            + "(attribute_not_exists(#state) AND #status = :legacyPending "
                            + "AND #reconcileShard = :shard AND #nextReconcileAt < :next"
                            + legacyIntentCondition + "))")
                    .expressionAttributeNames(Map.of(
                            "#state", STATE,
                            "#stateType", "type",
                            "#stateIntent", "paymentIntentId",
                            "#stateShard", "reconcileShard",
                            "#stateNext", "nextReconcileAt",
                            "#status", "status",
                            "#paymentIntentId", "paymentIntentId",
                            "#reconcileShard", "reconcileShard",
                            "#nextReconcileAt", "nextReconcileAt"))
                    .expressionAttributeValues(values)
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
                .map(DynamoBookingRepository::bookingFromStoredItem)
                .toList();
    }

    private static Booking bookingFromStoredItem(Map<String, AttributeValue> item) {
        Booking booking = bookingFromItem(item);
        return requireBookingIdentity(item, booking, booking.id());
    }

    private static Booking requireBookingIdentity(
            Map<String, AttributeValue> item, Booking booking, BookingId expectedId) {
        AttributeValue pk = item.get(PK);
        if (!booking.id().equals(expectedId)
                || pk == null
                || pk.s() == null
                || !DynamoKeys.bookingPk(expectedId).equals(pk.s())) {
            throw new IllegalStateException("booking record identity mismatch for " + expectedId.value());
        }
        return booking;
    }

    private static void requireMappingValue(Map<String, AttributeValue> mapping, String name, String expected) {
        AttributeValue value = mapping.get(name);
        if (value == null || value.s() == null || !expected.equals(value.s())) {
            throw new IllegalStateException("checkout idempotency record has inconsistent " + name);
        }
    }
}
