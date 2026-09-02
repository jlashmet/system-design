package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.CheckoutConflictException;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import io.floci.testcontainers.FlociContainer;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@Testcontainers
class DynamoCheckoutStartScopeIntegrityIT {
    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-123");
    private static final SeatId SEAT_ID = new SeatId("A10");
    private static final Price PRICE = new Price(new BigDecimal("100.00"), Currency.getInstance("USD"));

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private String tableName;

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) {
                dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            }
            dynamoDb.close();
        }
    }

    @Test
    void checkoutStartRejectsStoredHoldScopeDrift() {
        initializeDynamo();
        DynamoHoldRepository holdRepository = new DynamoHoldRepository(dynamoDb, tableName);
        DynamoCheckoutGateway gateway = new DynamoCheckoutGateway(dynamoDb, tableName);

        putAvailableSeat();
        SeatPriceQuote quote = holdRepository.quoteSeatPrices(EVENT_ID, Set.of(SEAT_ID));
        Hold active = Hold.active(
                new HoldId("hold-1"), USER_ID, EVENT_ID, Set.of(SEAT_ID), quote.totalPrice(),
                NOW, NOW.plusSeconds(300));
        holdRepository.createWithSeatClaims(active, quote, NOW, new HoldIdempotencyKey("hold-key"));

        Hold checkout = active.startCheckout(NOW.plusSeconds(10), NOW.plusSeconds(120));
        ReservationCheckout reservation = ReservationTestFixtures.from(checkout);
        Booking pending = Booking.pending(
                new BookingId("booking-1"), reservation, "checkout-key",
                NOW.plusSeconds(10), NOW.plusSeconds(40), 1);
        overwriteStoredHoldUser(active.id(), new UserId("user-corrupt"));

        assertThatThrownBy(() -> gateway.startCheckout(reservation, pending))
                .isInstanceOf(CheckoutConflictException.class);

        assertThat(rawHold(active.id()).get("status").s()).isEqualTo("ACTIVE");
        assertThat(rawBooking(pending.id())).isEmpty();
        assertThat(rawSeat().get("status").s()).isEqualTo("HELD");
    }

    private void initializeDynamo() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-start-scope-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("reconcileShard").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("nextReconcileAt").attributeType(ScalarAttributeType.N).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(DynamoItemCodec.RECONCILIATION_INDEX)
                        .keySchema(
                                KeySchemaElement.builder().attributeName("reconcileShard").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("nextReconcileAt").keyType(KeyType.RANGE).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .build());
    }

    private void putAvailableSeat() {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", string(DynamoKeys.seatPk(EVENT_ID, SEAT_ID)),
                        "entityType", string("SEAT"),
                        "eventId", string(EVENT_ID.value()),
                        "seatId", string(SEAT_ID.value()),
                        "status", string("AVAILABLE"),
                        "priceAmount", string(PRICE.amount().toPlainString()),
                        "priceCurrency", string(PRICE.currency().getCurrencyCode())))
                .build());
    }

    private void overwriteStoredHoldUser(HoldId holdId, UserId userId) {
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("pk", string(DynamoKeys.holdPk(holdId))))
                .updateExpression("SET #userId = :userId")
                .expressionAttributeNames(Map.of("#userId", "userId"))
                .expressionAttributeValues(Map.of(":userId", string(userId.value())))
                .build());
    }

    private Map<String, AttributeValue> rawHold(HoldId holdId) {
        return rawItem(DynamoKeys.holdPk(holdId));
    }

    private Map<String, AttributeValue> rawBooking(BookingId bookingId) {
        return rawItem(DynamoKeys.bookingPk(bookingId));
    }

    private Map<String, AttributeValue> rawSeat() {
        return rawItem(DynamoKeys.seatPk(EVENT_ID, SEAT_ID));
    }

    private Map<String, AttributeValue> rawItem(String pk) {
        return dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", string(pk)))
                        .consistentRead(true)
                        .build())
                .item();
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
