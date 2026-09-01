package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
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
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class DynamoHoldIdempotencyIntegrityIT {
    private static final EventId EVENT_ID = new EventId("event-1");
    private static final UserId USER_ID = new UserId("user-1");
    private static final Instant NOW = Instant.parse("2026-08-27T22:00:00Z");

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private String tableName;
    private HoldIdempotencyKey key;
    private DynamoHoldRepository repository;
    private Throwable thrown;

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
    void mappingToMissingHoldFailsClosedInsteadOfLookingLikeANewRequest() {
        initialize();
        key = new HoldIdempotencyKey("retry-key");
        putIdempotencyMapping("missing-hold");

        whenMappingIsResolved();

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("references missing hold");
    }

    @Test
    void directLookupRejectsHoldWhoseStoredIdDisagreesWithPrimaryKey() {
        initialize();
        putHold("hold-1", "different-hold-id");

        assertThatThrownBy(() -> repository.findById(new HoldId("hold-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("hold record identity mismatch for hold-1");
    }

    @Test
    void idempotencyLookupRejectsHoldWhoseStoredIdDisagreesWithPrimaryKey() {
        initialize();
        key = new HoldIdempotencyKey("retry-key");
        putHold("hold-1", "different-hold-id");
        putIdempotencyMapping("hold-1");

        whenMappingIsResolved();

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("hold record identity mismatch for hold-1");
    }

    private void initialize() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-booking-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk")
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(KeySchemaElement.builder()
                        .attributeName("pk")
                        .keyType(KeyType.HASH)
                        .build())
                .build());
        repository = new DynamoHoldRepository(dynamoDb, tableName);
        key = null;
        thrown = null;
    }

    private void putIdempotencyMapping(String holdId) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", string(DynamoReservationKeys.holdIdempotencyPk(EVENT_ID, USER_ID, key)),
                        "entityType", string("HOLD_IDEMPOTENCY"),
                        "eventId", string(EVENT_ID.value()),
                        "userId", string(USER_ID.value()),
                        "idempotencyKey", string(key.value()),
                        "holdId", string(holdId)))
                .build());
    }

    private void putHold(String keyHoldId, String storedHoldId) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.ofEntries(
                        Map.entry("pk", string(DynamoReservationKeys.holdPk(new HoldId(keyHoldId)))),
                        Map.entry("entityType", string("HOLD")),
                        Map.entry("holdId", string(storedHoldId)),
                        Map.entry("userId", string(USER_ID.value())),
                        Map.entry("eventId", string(EVENT_ID.value())),
                        Map.entry("seatIds", AttributeValue.builder().ss("A10").build()),
                        Map.entry("totalPriceAmount", string("100.00")),
                        Map.entry("totalPriceCurrency", string("USD")),
                        Map.entry("status", string("ACTIVE")),
                        Map.entry("expiresAt", number(NOW.plusSeconds(300).toEpochMilli())),
                        Map.entry("createdAt", number(NOW.toEpochMilli()))))
                .build());
    }

    private void whenMappingIsResolved() {
        try {
            repository.findByIdempotencyKey(EVENT_ID, USER_ID, key);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
