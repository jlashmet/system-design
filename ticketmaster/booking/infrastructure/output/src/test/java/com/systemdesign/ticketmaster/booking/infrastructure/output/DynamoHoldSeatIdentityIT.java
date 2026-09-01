package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import io.floci.testcontainers.FlociContainer;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.LinkedHashMap;
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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class DynamoHoldSeatIdentityIT {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final SeatId SEAT_ID = new SeatId("A10");
    private static final Instant NOW = Instant.parse("2026-08-27T22:00:00Z");
    private static final Price PRICE = new Price(new BigDecimal("100.00"), Currency.getInstance("USD"));

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private String tableName;
    private DynamoHoldRepository repository;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-seat-identity-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder()
                        .attributeName("pk").keyType(KeyType.HASH).build())
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
    void quoteRejectsSeatWhosePayloadIdentityDisagreesWithRequestedKey() {
        putSeat("other-event", SEAT_ID.value());

        assertThatThrownBy(() -> repository.quoteSeatPrices(EVENT_ID, Set.of(SEAT_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seat identity mismatch");
    }

    @Test
    void claimRejectsSeatWhosePayloadIdentityChangesAfterQuoteWithoutMutatingIt() {
        putSeat(EVENT_ID.value(), SEAT_ID.value());
        SeatPriceQuote quote = repository.quoteSeatPrices(EVENT_ID, Set.of(SEAT_ID));
        putSeat("other-event", SEAT_ID.value());
        Hold hold = Hold.active(
                new HoldId("hold-1"), USER_ID, EVENT_ID, Set.of(SEAT_ID), quote.totalPrice(),
                NOW, NOW.plus(5, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> repository.createWithSeatClaims(
                        hold, quote, NOW, new HoldIdempotencyKey("key-1")))
                .isInstanceOf(RuntimeException.class);

        Map<String, AttributeValue> stored = seatItem();
        assertThat(stored.get("eventId").s()).isEqualTo("other-event");
        assertThat(stored.get("status").s()).isEqualTo("AVAILABLE");
        assertThat(stored).doesNotContainKeys("holdId", "holdExpiresAt");
        assertThat(repository.findById(hold.id())).isEmpty();
    }

    private void putSeat(String eventId, String seatId) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", string(DynamoKeys.seatPk(EVENT_ID, SEAT_ID)));
        item.put("entityType", string("SEAT"));
        item.put("eventId", string(eventId));
        item.put("seatId", string(seatId));
        item.put("status", string("AVAILABLE"));
        item.put("priceAmount", string(PRICE.amount().toPlainString()));
        item.put("priceCurrency", string(PRICE.currency().getCurrencyCode()));
        dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    private Map<String, AttributeValue> seatItem() {
        return dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", string(DynamoKeys.seatPk(EVENT_ID, SEAT_ID))))
                        .consistentRead(true)
                        .build())
                .item();
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
