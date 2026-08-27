package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatClaimConflictException;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
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
class DynamoHoldRepositoryIT {
    private static final Instant NOW = Instant.parse("2026-08-27T22:00:00Z");
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final Price TWO_HUNDRED_DOLLARS = new Price(new BigDecimal("200.00"), Currency.getInstance("USD"));

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoHoldRepository repository;
    private String tableName;
    private Hold requestedHold;
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
    void createsHoldAndClaimsAllSeats() {
        given(availableSeat("A10"), availableSeat("A11"));
        whenCreateHold(activeHold("hold-1", "A10", "A11"));
        thenExpectHeldBy("hold-1", "A10", "A11");
    }

    @Test
    void transactionRollsBackWhenAnySeatIsUnavailable() {
        given(availableSeat("A10"), heldSeat("A11", "other-hold", NOW.plus(5, ChronoUnit.MINUTES)));
        whenCreateHold(activeHold("hold-2", "A10", "A11"));
        thenExpectConflictAndAvailable("A10");
    }

    @Test
    void reclaimsExpiredHeldSeatWithoutCleanupWorker() {
        given(heldSeat("A10", "old-hold", NOW.minus(1, ChronoUnit.SECONDS)));
        whenCreateHold(activeHold("hold-3", "A10"));
        thenExpectHeldBy("hold-3", "A10");
    }

    private void given(SeatFixture... seats) {
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
        for (SeatFixture seat : seats) {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(seat.toItem())
                    .build());
        }
    }

    private void whenCreateHold(Hold hold) {
        requestedHold = hold;
        thrown = null;
        try {
            repository.createWithSeatClaims(hold, NOW);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectHeldBy(String holdId, String... seatIds) {
        assertThat(thrown).isNull();
        assertThat(repository.findById(new HoldId(holdId))).contains(requestedHold);
        for (String seatId : seatIds) {
            Map<String, AttributeValue> item = seatItem(seatId);
            assertThat(item.get("status").s()).isEqualTo("HELD");
            assertThat(item.get("holdId").s()).isEqualTo(holdId);
            assertThat(Long.parseLong(item.get("holdExpiresAt").n())).isEqualTo(requestedHold.expiresAt().toEpochMilli());
        }
    }

    private void thenExpectConflictAndAvailable(String seatId) {
        assertThat(thrown).isInstanceOf(SeatClaimConflictException.class);
        assertThat(repository.findById(requestedHold.id())).isEmpty();
        Map<String, AttributeValue> item = seatItem(seatId);
        assertThat(item.get("status").s()).isEqualTo("AVAILABLE");
        assertThat(item).doesNotContainKeys("holdId", "holdExpiresAt");
    }

    private Map<String, AttributeValue> seatItem(String seatId) {
        return dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", string(seatPk(seatId))))
                        .consistentRead(true)
                        .build())
                .item();
    }

    private static Hold activeHold(String holdId, String... seatIds) {
        return Hold.active(
                new HoldId(holdId),
                USER_ID,
                EVENT_ID,
                Set.of(java.util.Arrays.stream(seatIds).map(SeatId::new).toArray(SeatId[]::new)),
                TWO_HUNDRED_DOLLARS,
                NOW,
                NOW.plus(5, ChronoUnit.MINUTES));
    }

    private static SeatFixture availableSeat(String seatId) {
        return new SeatFixture(seatId, "AVAILABLE", null, null);
    }

    private static SeatFixture heldSeat(String seatId, String holdId, Instant expiresAt) {
        return new SeatFixture(seatId, "HELD", holdId, expiresAt);
    }

    private static String seatPk(String seatId) {
        return "EVENT#" + EVENT_ID.value() + "#SEAT#" + seatId;
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private record SeatFixture(String seatId, String status, String holdId, Instant expiresAt) {
        Map<String, AttributeValue> toItem() {
            Map<String, AttributeValue> item = new LinkedHashMap<>();
            item.put("pk", string(seatPk(seatId)));
            item.put("entityType", string("SEAT"));
            item.put("status", string(status));
            if (holdId != null) {
                item.put("holdId", string(holdId));
            }
            if (expiresAt != null) {
                item.put("holdExpiresAt", number(expiresAt.toEpochMilli()));
            }
            return item;
        }
    }
}
