package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.HoldStatus;
import com.systemdesign.ticketmaster.booking.domain.Price;
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

/**
 * Reservation storage is intentionally read/quote-only. Seat claims and creation
 * of the reservation record happen atomically in DynamoCheckoutGateway when the
 * customer enters checkout.
 */
public final class DynamoHoldRepository implements HoldRepository {
    private static final String PK = "pk";
    private static final int MAX_QUOTE_BATCH_ATTEMPTS = 5;
    private static final long INITIAL_QUOTE_BACKOFF_MILLIS = 10L;

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoHoldRepository(DynamoDbClient dynamoDb, String tableName, String ignoredLocalRegion) {
        this(dynamoDb, tableName);
    }

    public DynamoHoldRepository(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
    }

    @Override
    public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(seatIds, "seatIds");
        if (seatIds.isEmpty()) throw new IllegalArgumentException("at least one seat is required");
        if (seatIds.size() > 96) throw new IllegalArgumentException("checkout cannot contain more than 96 seats");

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
    public Optional<Hold> findById(HoldId holdId) {
        Objects.requireNonNull(holdId, "holdId");
        Map<String, AttributeValue> item = DynamoReservationCall.execute(
                        "checkout reservation lookup",
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
            throw new IllegalStateException("checkout reservation identity mismatch for " + holdId.value());
        }
        return Optional.of(hold);
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

    private Hold fromItem(Map<String, AttributeValue> item) {
        Set<SeatId> seatIds = item.get("seatIds").ss().stream()
                .map(SeatId::new)
                .collect(Collectors.toUnmodifiableSet());
        AttributeValue deadline = item.get("checkoutExpiresAt");
        if (deadline == null) {
            // Read compatibility for checkout reservations written before the
            // pre-checkout hold deadline was removed.
            deadline = item.get("expiresAt");
        }
        if (deadline == null || deadline.n() == null) {
            throw new IllegalStateException("checkout reservation is missing checkoutExpiresAt");
        }
        return new Hold(
                new HoldId(item.get("holdId").s()),
                new UserId(item.get("userId").s()),
                new EventId(item.get("eventId").s()),
                seatIds,
                new Price(new BigDecimal(item.get("totalPriceAmount").s()),
                        Currency.getInstance(item.get("totalPriceCurrency").s())),
                HoldStatus.valueOf(item.get("status").s()),
                Instant.ofEpochMilli(Long.parseLong(deadline.n())),
                Instant.ofEpochMilli(Long.parseLong(item.get("createdAt").n())));
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

    private static String stringValue(AttributeValue value) { return value == null ? null : value.s(); }
    private static String seatPk(EventId eventId, SeatId seatId) { return DynamoReservationKeys.seatPk(eventId, seatId); }
    private static String holdPk(HoldId holdId) { return DynamoReservationKeys.holdPk(holdId); }
    private static AttributeValue string(String value) { return AttributeValue.builder().s(value).build(); }
}
