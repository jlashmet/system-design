package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventOwnershipUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.HoldStatus;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatClaimConflictException;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.SeatUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.reservation.infrastructure.ReservationStorageUnavailableException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

public final class DynamoHoldRepository implements HoldRepository {
    private static final String PK = "pk";
    private static final int MAX_QUOTE_BATCH_ATTEMPTS = 5;
    private static final long INITIAL_QUOTE_BACKOFF_MILLIS = 10L;

    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final String localRegion;

    public DynamoHoldRepository(DynamoDbClient dynamoDb, String tableName, String localRegion) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.localRegion = requireText(localRegion, "localRegion");
    }

    DynamoHoldRepository(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.localRegion = null;
    }

    @Override
    public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(seatIds, "seatIds");
        if (seatIds.isEmpty()) throw new IllegalArgumentException("at least one seat is required");
        if (seatIds.size() > 97) throw new IllegalArgumentException("a hold cannot contain more than 97 seats");

        Map<String, SeatId> seatByPk = new LinkedHashMap<>();
        List<Map<String, AttributeValue>> keys = new ArrayList<>(seatIds.size());
        for (SeatId seatId : seatIds) {
            String pk = seatPk(eventId, seatId);
            seatByPk.put(pk, seatId);
            keys.add(Map.of(PK, string(pk)));
        }

        Map<SeatId, Price> prices = new HashMap<>();
        Map<String, KeysAndAttributes> requestItems = Map.of(
                tableName,
                KeysAndAttributes.builder().keys(keys).consistentRead(true).build());

        int attempt = 0;
        while (!requestItems.isEmpty()) {
            attempt++;
            Map<String, KeysAndAttributes> currentRequestItems = requestItems;
            BatchGetItemResponse response = DynamoReservationCall.execute(
                    "authoritative seat price quote",
                    () -> dynamoDb.batchGetItem(BatchGetItemRequest.builder()
                            .requestItems(currentRequestItems)
                            .build()));
            for (Map<String, AttributeValue> item : response.responses().getOrDefault(tableName, List.of())) {
                AttributeValue pkValue = item.get(PK);
                SeatId seatId = pkValue == null ? null : seatByPk.get(pkValue.s());
                if (seatId == null) throw new IllegalStateException("authoritative seat batch returned an unexpected item");
                prices.put(seatId, priceFromSeatItem(eventId, seatId, item));
            }

            requestItems = response.unprocessedKeys();
            if (requestItems == null || requestItems.isEmpty()) break;
            if (attempt >= MAX_QUOTE_BATCH_ATTEMPTS) {
                throw new ReservationStorageUnavailableException(
                        "authoritative seat price quote",
                        new IllegalStateException(
                                "DynamoDB still returned unprocessed seat keys after " + attempt + " attempts"));
            }
            backoffBeforeQuoteRetry(attempt);
        }

        for (SeatId seatId : seatIds) {
            if (!prices.containsKey(seatId)) throw new SeatUnavailableException(seatId);
        }
        return new SeatPriceQuote(eventId, prices);
    }

    @Override
    public void createWithSeatClaims(
            Hold hold, SeatPriceQuote quote, Instant now, HoldIdempotencyKey idempotencyKey) {
        Objects.requireNonNull(hold, "hold");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (!quote.eventId().equals(hold.eventId()) || !quote.seatIds().equals(hold.seatIds())) {
            throw new IllegalArgumentException("seat price quote must match hold event and seats");
        }
        if (!quote.totalPrice().equals(hold.totalPrice())) {
            throw new IllegalArgumentException("hold total must match authoritative seat price quote");
        }
        if (hold.seatIds().size() > 97) throw new IllegalArgumentException("a hold cannot contain more than 97 seats");

        DynamoReservationEventFence.Fence fence = localRegion == null
                ? null
                : DynamoReservationEventFence.resolve(dynamoDb, tableName, hold.eventId(), localRegion);
        List<TransactWriteItem> writes = new ArrayList<>();
        if (fence != null) {
            writes.add(DynamoReservationEventFence.condition(tableName, hold.eventId(), fence));
        }
        for (SeatId seatId : hold.seatIds()) {
            writes.add(TransactWriteItem.builder()
                    .update(seatClaimUpdate(hold, seatId, quote.prices().get(seatId), now))
                    .build());
        }
        writes.add(TransactWriteItem.builder().put(Put.builder()
                .tableName(tableName)
                .item(toItem(hold))
                .conditionExpression("attribute_not_exists(#pk)")
                .expressionAttributeNames(Map.of("#pk", PK))
                .build()).build());
        writes.add(TransactWriteItem.builder().put(Put.builder()
                .tableName(tableName)
                .item(Map.of(
                        PK, string(DynamoReservationKeys.holdIdempotencyPk(hold.eventId(), hold.userId(), idempotencyKey)),
                        "entityType", string("HOLD_IDEMPOTENCY"),
                        "eventId", string(hold.eventId().value()),
                        "userId", string(hold.userId().value()),
                        "idempotencyKey", string(idempotencyKey.value()),
                        "holdId", string(hold.id().value())))
                .conditionExpression("attribute_not_exists(#pk)")
                .expressionAttributeNames(Map.of("#pk", PK))
                .build()).build());

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(writes).build());
        } catch (TransactionCanceledException e) {
            if (fence != null) assertFenceUnchanged(hold.eventId(), fence);
            if (DynamoReservationTransactionCancellation.hasNonConditionalFailure(e)) {
                throw new ReservationStorageUnavailableException("seat claim transaction", e);
            }
            throw new SeatClaimConflictException(hold.eventId(), hold.seatIds());
        } catch (software.amazon.awssdk.services.dynamodb.model.DynamoDbException
                 | software.amazon.awssdk.core.exception.SdkClientException unavailable) {
            throw new ReservationStorageUnavailableException("seat claim transaction", unavailable);
        }
    }

    @Override
    public Optional<Hold> findById(HoldId holdId) {
        Objects.requireNonNull(holdId, "holdId");
        Map<String, AttributeValue> item = DynamoReservationCall.execute(
                        "hold lookup",
                        () -> dynamoDb.getItem(GetItemRequest.builder()
                                .tableName(tableName)
                                .key(Map.of(PK, string(holdPk(holdId))))
                                .consistentRead(true)
                                .build()))
                .item();
        if (item == null || item.isEmpty()) return Optional.empty();

        Hold hold = fromItem(item);
        AttributeValue pk = item.get(PK);
        if (!hold.id().equals(holdId)
                || pk == null
                || pk.s() == null
                || !holdPk(holdId).equals(pk.s())) {
            throw new IllegalStateException("hold record identity mismatch for " + holdId.value());
        }
        return Optional.of(hold);
    }

    @Override
    public Optional<Hold> findByIdempotencyKey(
            EventId eventId, UserId userId, HoldIdempotencyKey idempotencyKey) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Map<String, AttributeValue> mapping = DynamoReservationCall.execute(
                        "hold idempotency lookup",
                        () -> dynamoDb.getItem(GetItemRequest.builder()
                                .tableName(tableName)
                                .key(Map.of(PK, string(DynamoReservationKeys.holdIdempotencyPk(eventId, userId, idempotencyKey))))
                                .consistentRead(true)
                                .build()))
                .item();
        if (mapping == null || mapping.isEmpty()) return Optional.empty();
        requireMappingValue(mapping, "eventId", eventId.value());
        requireMappingValue(mapping, "userId", userId.value());
        requireMappingValue(mapping, "idempotencyKey", idempotencyKey.value());
        AttributeValue holdId = mapping.get("holdId");
        if (holdId == null || holdId.s() == null || holdId.s().isBlank()) {
            throw new IllegalStateException("hold idempotency record is missing holdId");
        }
        Hold hold = findById(new HoldId(holdId.s()))
                .orElseThrow(() -> new IllegalStateException(
                        "hold idempotency record references missing hold " + holdId.s()));
        if (!hold.eventId().equals(eventId) || !hold.userId().equals(userId)) {
            throw new IllegalStateException("hold idempotency record resolved outside its event/user scope");
        }
        return Optional.of(hold);
    }

    @Override
    @Deprecated
    public Optional<Hold> findByIdempotencyKey(HoldIdempotencyKey idempotencyKey) {
        throw new UnsupportedOperationException("hold idempotency lookup requires event and user scope");
    }

    private void assertFenceUnchanged(EventId eventId, DynamoReservationEventFence.Fence expected) {
        DynamoReservationEventFence.Fence current =
                DynamoReservationEventFence.resolve(dynamoDb, tableName, eventId, localRegion);
        if (current.epoch() != expected.epoch()) {
            throw new EventOwnershipUnavailableException(eventId, "event write epoch changed during seat claim");
        }
    }

    private static void requireMappingValue(Map<String, AttributeValue> mapping, String name, String expected) {
        AttributeValue value = mapping.get(name);
        if (value == null || value.s() == null || !expected.equals(value.s())) {
            throw new IllegalStateException("hold idempotency record has inconsistent " + name);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static void backoffBeforeQuoteRetry(int completedAttempt) {
        long delayMillis = INITIAL_QUOTE_BACKOFF_MILLIS << Math.min(completedAttempt - 1, 4);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ReservationStorageUnavailableException("authoritative seat price quote", interrupted);
        }
    }

    private Price priceFromSeatItem(EventId eventId, SeatId seatId, Map<String, AttributeValue> item) {
        if (!seatPk(eventId, seatId).equals(stringValue(item.get(PK)))
                || !"SEAT".equals(stringValue(item.get("entityType")))
                || !eventId.value().equals(stringValue(item.get("eventId")))
                || !seatId.value().equals(stringValue(item.get("seatId")))) {
            throw new IllegalStateException(
                    "authoritative seat identity mismatch for " + eventId.value() + "/" + seatId.value());
        }
        AttributeValue amount = item.get("priceAmount");
        AttributeValue currency = item.get("priceCurrency");
        if (amount == null || amount.s() == null || currency == null || currency.s() == null) {
            throw new IllegalStateException("authoritative seat is missing price: " + seatId.value());
        }
        return new Price(new BigDecimal(amount.s()), Currency.getInstance(currency.s()));
    }

    private Update seatClaimUpdate(Hold hold, SeatId seatId, Price quotedPrice, Instant now) {
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(seatPk(hold.eventId(), seatId))))
                .updateExpression("SET #status = :held, #holdId = :holdId, #holdExpiresAt = :expires")
                .conditionExpression("#entityType = :seatType AND #eventId = :eventId AND #seatId = :seatId "
                        + "AND (#status = :available OR (#status = :held AND #holdExpiresAt <= :now)) "
                        + "AND #priceAmount = :priceAmount AND #priceCurrency = :priceCurrency")
                .expressionAttributeNames(Map.of(
                        "#entityType", "entityType", "#eventId", "eventId", "#seatId", "seatId",
                        "#status", "status", "#holdId", "holdId", "#holdExpiresAt", "holdExpiresAt",
                        "#priceAmount", "priceAmount", "#priceCurrency", "priceCurrency"))
                .expressionAttributeValues(Map.of(
                        ":seatType", string("SEAT"), ":eventId", string(hold.eventId().value()),
                        ":seatId", string(seatId.value()), ":available", string("AVAILABLE"),
                        ":held", string("HELD"), ":holdId", string(hold.id().value()),
                        ":expires", number(hold.expiresAt().toEpochMilli()), ":now", number(now.toEpochMilli()),
                        ":priceAmount", string(quotedPrice.amount().toPlainString()),
                        ":priceCurrency", string(quotedPrice.currency().getCurrencyCode())))
                .build();
    }

    private Map<String, AttributeValue> toItem(Hold hold) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, string(holdPk(hold.id())));
        item.put("entityType", string("HOLD"));
        item.put("holdId", string(hold.id().value()));
        item.put("userId", string(hold.userId().value()));
        item.put("eventId", string(hold.eventId().value()));
        item.put("seatIds", AttributeValue.builder().ss(hold.seatIds().stream().map(SeatId::value).sorted().toList()).build());
        item.put("totalPriceAmount", string(hold.totalPrice().amount().toPlainString()));
        item.put("totalPriceCurrency", string(hold.totalPrice().currency().getCurrencyCode()));
        item.put("status", string(hold.status().name()));
        item.put("expiresAt", number(hold.expiresAt().toEpochMilli()));
        item.put("createdAt", number(hold.createdAt().toEpochMilli()));
        if (hold.checkoutExpiresAt() != null) item.put("checkoutExpiresAt", number(hold.checkoutExpiresAt().toEpochMilli()));
        return item;
    }

    private Hold fromItem(Map<String, AttributeValue> item) {
        Set<SeatId> seatIds = item.get("seatIds").ss().stream().map(SeatId::new).collect(Collectors.toUnmodifiableSet());
        Instant checkoutExpiresAt = item.containsKey("checkoutExpiresAt")
                ? Instant.ofEpochMilli(Long.parseLong(item.get("checkoutExpiresAt").n())) : null;
        return new Hold(
                new HoldId(item.get("holdId").s()), new UserId(item.get("userId").s()),
                new EventId(item.get("eventId").s()), seatIds,
                new Price(new BigDecimal(item.get("totalPriceAmount").s()),
                        Currency.getInstance(item.get("totalPriceCurrency").s())),
                HoldStatus.valueOf(item.get("status").s()),
                Instant.ofEpochMilli(Long.parseLong(item.get("expiresAt").n())), checkoutExpiresAt,
                Instant.ofEpochMilli(Long.parseLong(item.get("createdAt").n())));
    }

    private static String stringValue(AttributeValue value) { return value == null ? null : value.s(); }
    private static String seatPk(EventId eventId, SeatId seatId) { return DynamoReservationKeys.seatPk(eventId, seatId); }
    private static String holdPk(HoldId holdId) { return DynamoReservationKeys.holdPk(holdId); }
    private static AttributeValue string(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue number(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
}
