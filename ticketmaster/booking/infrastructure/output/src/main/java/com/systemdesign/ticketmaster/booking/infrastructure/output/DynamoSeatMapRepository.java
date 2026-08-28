package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SeatStatus;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

public final class DynamoSeatMapRepository implements SeatMapRepository {
    private static final String PK = "pk";
    private static final String SK = "sk";
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoSeatMapRepository(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
    }

    @Override
    public void upsert(SeatMapSeat seat) {
        Objects.requireNonNull(seat, "seat");

        Map<String, AttributeValue> seatItem = new HashMap<>();
        seatItem.put(PK, string(sectionPk(seat.eventId(), seat.sectionId())));
        seatItem.put(SK, string(seatSk(seat.seatId())));
        seatItem.put("eventId", string(seat.eventId().value()));
        seatItem.put("sectionId", string(seat.sectionId().value()));
        seatItem.put("seatId", string(seat.seatId().value()));
        seatItem.put("row", string(seat.row()));
        seatItem.put("number", string(seat.number()));
        seatItem.put("priceAmount", string(seat.price().amount().toPlainString()));
        seatItem.put("priceCurrency", string(seat.price().currency().getCurrencyCode()));
        seatItem.put("status", string(seat.status().name()));
        if (seat.holdExpiresAt() != null) {
            seatItem.put("holdExpiresAt", number(seat.holdExpiresAt().toEpochMilli()));
        }

        Put seatPut = Put.builder()
                .tableName(tableName)
                .item(seatItem)
                .build();

        // Materialize a tiny event-level section directory so section discovery never scans seat rows.
        // A future static venue-geometry projection can own these markers if rewriting them on seat
        // changes becomes material at scale.
        Put sectionDirectoryPut = Put.builder()
                .tableName(tableName)
                .item(Map.of(
                        PK, string(eventPk(seat.eventId())),
                        SK, string(sectionSk(seat.sectionId())),
                        "eventId", string(seat.eventId().value()),
                        "sectionId", string(seat.sectionId().value())))
                .build();

        // Keep the derived seat row and its discovery marker consistent for each projected stream image.
        dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().put(seatPut).build(),
                        TransactWriteItem.builder().put(sectionDirectoryPut).build())
                .build());
    }

    @Override
    public List<SectionId> findSections(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return dynamoDb.query(QueryRequest.builder()
                        .tableName(tableName)
                        .keyConditionExpression("#pk = :pk AND begins_with(#sk, :sectionPrefix)")
                        .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                        .expressionAttributeValues(Map.of(
                                ":pk", string(eventPk(eventId)),
                                ":sectionPrefix", string("SECTION#")))
                        .scanIndexForward(true)
                        .consistentRead(false)
                        .build())
                .items().stream()
                .map(item -> new SectionId(item.get("sectionId").s()))
                .toList();
    }

    @Override
    public List<SeatMapSeat> findSection(EventId eventId, SectionId sectionId) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sectionId, "sectionId");
        return dynamoDb.query(QueryRequest.builder()
                        .tableName(tableName)
                        .keyConditionExpression("#pk = :pk")
                        .expressionAttributeNames(Map.of("#pk", PK))
                        .expressionAttributeValues(Map.of(":pk", string(sectionPk(eventId, sectionId))))
                        .scanIndexForward(true)
                        .consistentRead(false)
                        .build())
                .items().stream()
                .map(DynamoSeatMapRepository::fromItem)
                .toList();
    }

    static String sectionPk(EventId eventId, SectionId sectionId) {
        return eventPk(eventId) + "#SECTION#" + sectionId.value();
    }

    static String eventPk(EventId eventId) {
        return "EVENT#" + eventId.value();
    }

    private static String sectionSk(SectionId sectionId) {
        return "SECTION#" + sectionId.value();
    }

    private static String seatSk(SeatId seatId) {
        return "SEAT#" + seatId.value();
    }

    private static SeatMapSeat fromItem(Map<String, AttributeValue> item) {
        AttributeValue expiry = item.get("holdExpiresAt");
        Instant holdExpiresAt = expiry == null || expiry.n() == null
                ? null
                : Instant.ofEpochMilli(Long.parseLong(expiry.n()));
        return new SeatMapSeat(
                new EventId(item.get("eventId").s()),
                new SectionId(item.get("sectionId").s()),
                new SeatId(item.get("seatId").s()),
                item.get("row").s(),
                item.get("number").s(),
                new Price(new BigDecimal(item.get("priceAmount").s()),
                        Currency.getInstance(item.get("priceCurrency").s())),
                SeatStatus.valueOf(item.get("status").s()),
                holdExpiresAt);
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
