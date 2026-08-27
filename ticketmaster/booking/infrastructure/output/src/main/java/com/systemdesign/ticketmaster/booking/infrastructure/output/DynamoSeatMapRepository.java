package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SeatStatus;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

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
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        PK, string(sectionPk(seat.eventId(), seat.sectionId())),
                        SK, string(seatSk(seat.seatId())),
                        "eventId", string(seat.eventId().value()),
                        "sectionId", string(seat.sectionId().value()),
                        "seatId", string(seat.seatId().value()),
                        "row", string(seat.row()),
                        "number", string(seat.number()),
                        "priceAmount", string(seat.price().amount().toPlainString()),
                        "priceCurrency", string(seat.price().currency().getCurrencyCode()),
                        "status", string(seat.status().name())))
                .build());
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
        return "EVENT#" + eventId.value() + "#SECTION#" + sectionId.value();
    }

    private static String seatSk(SeatId seatId) {
        return "SEAT#" + seatId.value();
    }

    private static SeatMapSeat fromItem(Map<String, AttributeValue> item) {
        return new SeatMapSeat(
                new EventId(item.get("eventId").s()),
                new SectionId(item.get("sectionId").s()),
                new SeatId(item.get("seatId").s()),
                item.get("row").s(),
                item.get("number").s(),
                new Price(new BigDecimal(item.get("priceAmount").s()),
                        Currency.getInstance(item.get("priceCurrency").s())),
                SeatStatus.valueOf(item.get("status").s()));
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
