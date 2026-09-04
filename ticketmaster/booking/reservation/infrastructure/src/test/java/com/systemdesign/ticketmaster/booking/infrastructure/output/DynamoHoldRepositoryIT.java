package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldStatus;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.SeatUnavailableException;
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
import org.junit.jupiter.api.BeforeEach;
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
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class DynamoHoldRepositoryIT {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final Instant NOW = Instant.parse("2026-08-27T22:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(600);

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoHoldRepository repository;
    private String tableName;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-reservation-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .build());
        repository = new DynamoHoldRepository(dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void quotesAuthoritativePricesForSelectedSeats() {
        putSeat("A10", price("100.00"));
        putSeat("A11", price("125.00"));

        SeatPriceQuote quote = repository.quoteSeatPrices(
                EVENT_ID, Set.of(new SeatId("A10"), new SeatId("A11")));

        assertThat(quote.prices()).containsExactlyInAnyOrderEntriesOf(Map.of(
                new SeatId("A10"), price("100.00"),
                new SeatId("A11"), price("125.00")));
        assertThat(quote.totalPrice()).isEqualTo(price("225.00"));
    }

    @Test
    void missingAuthoritativeSeatFailsQuote() {
        putSeat("A10", price("100.00"));

        assertThatThrownBy(() -> repository.quoteSeatPrices(
                EVENT_ID, Set.of(new SeatId("A10"), new SeatId("A11"))))
                .isInstanceOf(SeatUnavailableException.class);
    }

    @Test
    void readsCheckoutReservationCreatedByCheckoutTransaction() {
        HoldId checkoutId = new HoldId("checkout-1");
        putCheckoutReservation(checkoutId);

        var reservation = repository.findById(checkoutId).orElseThrow();

        assertThat(reservation.id()).isEqualTo(checkoutId);
        assertThat(reservation.userId()).isEqualTo(USER_ID);
        assertThat(reservation.eventId()).isEqualTo(EVENT_ID);
        assertThat(reservation.seatIds()).containsExactly(new SeatId("A10"));
        assertThat(reservation.status()).isEqualTo(HoldStatus.CHECKOUT_IN_PROGRESS);
        assertThat(reservation.checkoutExpiresAt()).isEqualTo(DEADLINE);
        assertThat(reservation.createdAt()).isEqualTo(NOW);
    }

    private void putSeat(String seatId, Price price) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", string(DynamoReservationKeys.seatPk(EVENT_ID, new SeatId(seatId))),
                        "entityType", string("SEAT"),
                        "eventId", string(EVENT_ID.value()),
                        "seatId", string(seatId),
                        "status", string("AVAILABLE"),
                        "priceAmount", string(price.amount().toPlainString()),
                        "priceCurrency", string(price.currency().getCurrencyCode())))
                .build());
    }

    private void putCheckoutReservation(HoldId id) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.ofEntries(
                        Map.entry("pk", string(DynamoReservationKeys.holdPk(id))),
                        Map.entry("entityType", string("HOLD")),
                        Map.entry("holdId", string(id.value())),
                        Map.entry("userId", string(USER_ID.value())),
                        Map.entry("eventId", string(EVENT_ID.value())),
                        Map.entry("seatIds", AttributeValue.builder().ss("A10").build()),
                        Map.entry("totalPriceAmount", string("100.00")),
                        Map.entry("totalPriceCurrency", string("USD")),
                        Map.entry("status", string("CHECKOUT_IN_PROGRESS")),
                        Map.entry("checkoutExpiresAt", number(DEADLINE.toEpochMilli())),
                        Map.entry("createdAt", number(NOW.toEpochMilli()))))
                .build());
    }

    private static Price price(String amount) {
        return new Price(new BigDecimal(amount), Currency.getInstance("USD"));
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
