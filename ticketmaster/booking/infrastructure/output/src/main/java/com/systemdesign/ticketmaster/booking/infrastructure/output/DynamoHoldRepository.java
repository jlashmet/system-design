package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.HoldStatus;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatClaimConflictException;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

public final class DynamoHoldRepository implements HoldRepository {
    private static final String PK = "pk";
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoHoldRepository(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
    }

    @Override
    public void createWithSeatClaims(Hold hold, Instant now) {
        Objects.requireNonNull(hold, "hold");
        Objects.requireNonNull(now, "now");
        if (hold.seatIds().size() > 99) {
            throw new IllegalArgumentException("a hold cannot contain more than 99 seats");
        }

        List<TransactWriteItem> writes = new ArrayList<>();
        for (SeatId seatId : hold.seatIds()) {
            writes.add(TransactWriteItem.builder()
                    .update(seatClaimUpdate(hold, seatId, now))
                    .build());
        }
        writes.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(toItem(hold))
                        .conditionExpression("attribute_not_exists(#pk)")
                        .expressionAttributeNames(Map.of("#pk", PK))
                        .build())
                .build());

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(writes)
                    .build());
        } catch (TransactionCanceledException e) {
            throw new SeatClaimConflictException(hold.eventId(), hold.seatIds());
        }
    }

    @Override
    public Optional<Hold> findById(HoldId holdId) {
        Objects.requireNonNull(holdId, "holdId");
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of(PK, string(holdPk(holdId))))
                        .consistentRead(true)
                        .build())
                .item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromItem(item));
    }

    private Update seatClaimUpdate(Hold hold, SeatId seatId, Instant now) {
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(seatPk(hold.eventId(), seatId))))
                .updateExpression("SET #status = :held, #holdId = :holdId, #holdExpiresAt = :expires")
                .conditionExpression("#status = :available OR (#status = :held AND #holdExpiresAt <= :now)")
                .expressionAttributeNames(Map.of(
                        "#status", "status",
                        "#holdId", "holdId",
                        "#holdExpiresAt", "holdExpiresAt"))
                .expressionAttributeValues(Map.of(
                        ":available", string("AVAILABLE"),
                        ":held", string("HELD"),
                        ":holdId", string(hold.id().value()),
                        ":expires", number(hold.expiresAt().toEpochMilli()),
                        ":now", number(now.toEpochMilli())))
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
        if (hold.checkoutExpiresAt() != null) {
            item.put("checkoutExpiresAt", number(hold.checkoutExpiresAt().toEpochMilli()));
        }
        return item;
    }

    private Hold fromItem(Map<String, AttributeValue> item) {
        Set<SeatId> seatIds = item.get("seatIds").ss().stream()
                .map(SeatId::new)
                .collect(Collectors.toUnmodifiableSet());
        Instant checkoutExpiresAt = item.containsKey("checkoutExpiresAt")
                ? Instant.ofEpochMilli(Long.parseLong(item.get("checkoutExpiresAt").n()))
                : null;
        return new Hold(
                new HoldId(item.get("holdId").s()),
                new UserId(item.get("userId").s()),
                new EventId(item.get("eventId").s()),
                seatIds,
                new Price(new BigDecimal(item.get("totalPriceAmount").s()), Currency.getInstance(item.get("totalPriceCurrency").s())),
                HoldStatus.valueOf(item.get("status").s()),
                Instant.ofEpochMilli(Long.parseLong(item.get("expiresAt").n())),
                checkoutExpiresAt,
                Instant.ofEpochMilli(Long.parseLong(item.get("createdAt").n())));
    }

    private static String seatPk(EventId eventId, SeatId seatId) {
        return "EVENT#" + eventId.value() + "#SEAT#" + seatId.value();
    }

    private static String holdPk(HoldId holdId) {
        return "HOLD#" + holdId.value();
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
