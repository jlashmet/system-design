package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SeatStatus;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import com.systemdesign.ticketmaster.booking.infrastructure.common.BookingStorageUnavailableException;
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
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

public final class DynamoSeatMapRepository implements SeatMapRepository {
    private static final String PK = "pk";
    private static final String SK = "sk";
    private static final String SECTION_PREFIX = "SECTION#";
    private static final String SEAT_PREFIX = "SEAT#";
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
        try {
            DynamoBookingCall.execute("seat-map projection transaction", () -> dynamoDb.transactWriteItems(
                    TransactWriteItemsRequest.builder()
                            .transactItems(
                                    TransactWriteItem.builder().put(seatPut).build(),
                                    TransactWriteItem.builder().put(sectionDirectoryPut).build())
                            .build()));
        } catch (TransactionCanceledException cancellation) {
            // This transaction has no conditional business rule. Any cancellation is therefore
            // infrastructure pressure/failure rather than a customer-visible booking conflict.
            throw new BookingStorageUnavailableException("seat-map projection transaction", cancellation);
        }
    }

    @Override
    public List<SectionId> findSections(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return DynamoBookingCall.execute(
                        "seat-map section directory read",
                        () -> dynamoDb.query(QueryRequest.builder()
                                .tableName(tableName)
                                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :sectionPrefix)")
                                .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                                .expressionAttributeValues(Map.of(
                                        ":pk", string(eventPk(eventId)),
                                        ":sectionPrefix", string(SECTION_PREFIX)))
                                .scanIndexForward(true)
                                .consistentRead(false)
                                .build()))
                .items().stream()
                .map(item -> sectionFromDirectoryItem(item, eventId))
                .toList();
    }

    @Override
    public List<SeatMapSeat> findSection(EventId eventId, SectionId sectionId) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sectionId, "sectionId");
        return DynamoBookingCall.execute(
                        "seat-map section read",
                        () -> dynamoDb.query(QueryRequest.builder()
                                .tableName(tableName)
                                .keyConditionExpression("#pk = :pk")
                                .expressionAttributeNames(Map.of("#pk", PK))
                                .expressionAttributeValues(Map.of(":pk", string(sectionPk(eventId, sectionId))))
                                .scanIndexForward(true)
                                .consistentRead(false)
                                .build()))
                .items().stream()
                .map(item -> seatFromStoredItem(item, eventId, sectionId))
                .toList();
    }

    static String sectionPk(EventId eventId, SectionId sectionId) {
        return eventPk(eventId) + "#SECTION#" + sectionId.value();
    }

    static String eventPk(EventId eventId) {
        return "EVENT#" + eventId.value();
    }

    private static String sectionSk(SectionId sectionId) {
        return SECTION_PREFIX + sectionId.value();
    }

    private static String seatSk(SeatId seatId) {
        return SEAT_PREFIX + seatId.value();
    }

    private static SectionId sectionFromDirectoryItem(Map<String, AttributeValue> item, EventId expectedEventId) {
        String pk = stringValue(item.get(PK));
        String sk = stringValue(item.get(SK));
        String storedEventId = stringValue(item.get("eventId"));
        String storedSectionId = stringValue(item.get("sectionId"));
        String keyedSectionId = suffix(sk, SECTION_PREFIX);
        String displaySectionId = keyedSectionId == null ? "unknown" : keyedSectionId;
        if (!eventPk(expectedEventId).equals(pk)
                || keyedSectionId == null
                || keyedSectionId.isBlank()
                || !expectedEventId.value().equals(storedEventId)
                || !keyedSectionId.equals(storedSectionId)) {
            throw new IllegalStateException(
                    "seat-map section directory identity mismatch for "
                            + expectedEventId.value() + "/" + displaySectionId);
        }
        return new SectionId(keyedSectionId);
    }

    private static SeatMapSeat seatFromStoredItem(
            Map<String, AttributeValue> item, EventId expectedEventId, SectionId expectedSectionId) {
        String pk = stringValue(item.get(PK));
        String sk = stringValue(item.get(SK));
        String storedEventId = stringValue(item.get("eventId"));
        String storedSectionId = stringValue(item.get("sectionId"));
        String storedSeatId = stringValue(item.get("seatId"));
        String keyedSeatId = suffix(sk, SEAT_PREFIX);
        String displaySeatId = keyedSeatId == null ? "unknown" : keyedSeatId;
        if (!sectionPk(expectedEventId, expectedSectionId).equals(pk)
                || keyedSeatId == null
                || keyedSeatId.isBlank()
                || !expectedEventId.value().equals(storedEventId)
                || !expectedSectionId.value().equals(storedSectionId)
                || !keyedSeatId.equals(storedSeatId)) {
            throw new IllegalStateException(
                    "seat-map seat identity mismatch for "
                            + expectedEventId.value() + "/" + expectedSectionId.value() + "/" + displaySeatId);
        }
        return fromItem(item);
    }

    private static String suffix(String value, String prefix) {
        return value != null && value.startsWith(prefix) ? value.substring(prefix.length()) : null;
    }

    private static String stringValue(AttributeValue value) {
        return value == null ? null : value.s();
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
