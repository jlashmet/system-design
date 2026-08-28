package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatClaimConflictException;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import io.floci.testcontainers.FlociContainer;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private static final Currency USD = Currency.getInstance("USD");
    private static final Price ONE_HUNDRED_DOLLARS = price("100.00");
    private static final Price ONE_HUNDRED_TWENTY_FIVE_DOLLARS = price("125.00");

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
    void createsHoldAndClaimsAllSeatsAtAuthoritativeQuotedPrice() {
        given(availableSeat("A10", ONE_HUNDRED_DOLLARS), availableSeat("A11", ONE_HUNDRED_TWENTY_FIVE_DOLLARS));
        whenCreateHold("hold-1", "A10", "A11");
        thenExpectHeldBy("hold-1", "A10", "A11");
        assertThat(requestedHold.totalPrice()).isEqualTo(price("225.00"));
    }

    @Test
    void transactionRollsBackWhenAnySeatIsUnavailable() {
        given(availableSeat("A10", ONE_HUNDRED_DOLLARS),
                heldSeat("A11", "other-hold", NOW.plus(5, ChronoUnit.MINUTES), ONE_HUNDRED_DOLLARS));
        whenCreateHold("hold-2", "A10", "A11");
        thenExpectConflictAndAvailable("A10");
    }

    @Test
    void reclaimsExpiredHeldSeatWithoutCleanupWorker() {
        given(heldSeat("A10", "old-hold", NOW.minus(1, ChronoUnit.SECONDS), ONE_HUNDRED_DOLLARS));
        whenCreateHold("hold-3", "A10");
        thenExpectHeldBy("hold-3", "A10");
    }

    @Test
    void rejectsEntireClaimWhenSeatPriceChangesAfterQuote() {
        given(availableSeat("A10", ONE_HUNDRED_DOLLARS), availableSeat("A11", ONE_HUNDRED_DOLLARS));
        Set<SeatId> seats = seatIds("A10", "A11");
        SeatPriceQuote quote = repository.quoteSeatPrices(EVENT_ID, seats);
        putSeat(availableSeat("A11", ONE_HUNDRED_TWENTY_FIVE_DOLLARS));

        whenCreateHold(activeHold("hold-4", seats, quote.totalPrice()), quote);

        assertThat(thrown).isInstanceOf(SeatClaimConflictException.class);
        assertThat(repository.findById(new HoldId("hold-4"))).isEmpty();
        assertThat(seatItem("A10").get("status").s()).isEqualTo("AVAILABLE");
        assertThat(seatItem("A11").get("status").s()).isEqualTo("AVAILABLE");
    }

    @Test
    void exactlyOneConcurrentContenderCanClaimTheSameSeat() throws Exception {
        given(availableSeat("A10", ONE_HUNDRED_DOLLARS));
        Set<SeatId> seats = seatIds("A10");
        SeatPriceQuote quote = repository.quoteSeatPrices(EVENT_ID, seats);

        List<RaceResult> results = race(16, index ->
                claim("race-" + index, seats, quote));

        List<RaceResult> winners = results.stream().filter(RaceResult::won).toList();
        assertThat(winners).hasSize(1);
        assertThat(results.stream().filter(result -> result.error() instanceof SeatClaimConflictException).count())
                .isEqualTo(15);
        assertThat(seatItem("A10").get("holdId").s()).isEqualTo(winners.getFirst().holdId());
    }

    @Test
    void overlappingMultiSeatClaimsRemainAllOrNothingUnderConcurrency() throws Exception {
        given(
                availableSeat("A10", ONE_HUNDRED_DOLLARS),
                availableSeat("A11", ONE_HUNDRED_DOLLARS),
                availableSeat("A12", ONE_HUNDRED_DOLLARS));

        Set<SeatId> leftSeats = seatIds("A10", "A11");
        Set<SeatId> rightSeats = seatIds("A11", "A12");
        SeatPriceQuote leftQuote = repository.quoteSeatPrices(EVENT_ID, leftSeats);
        SeatPriceQuote rightQuote = repository.quoteSeatPrices(EVENT_ID, rightSeats);

        List<RaceResult> results = race(List.of(
                () -> claim("left-hold", leftSeats, leftQuote),
                () -> claim("right-hold", rightSeats, rightQuote)));

        RaceResult winner = results.stream().filter(RaceResult::won).findFirst().orElseThrow();
        RaceResult loser = results.stream().filter(result -> !result.won()).findFirst().orElseThrow();
        assertThat(results.stream().filter(RaceResult::won)).hasSize(1);
        assertThat(loser.error()).isInstanceOf(SeatClaimConflictException.class);
        assertThat(repository.findById(new HoldId(loser.holdId()))).isEmpty();

        if (winner.holdId().equals("left-hold")) {
            assertHeldBy("A10", "left-hold");
            assertHeldBy("A11", "left-hold");
            assertAvailable("A12");
        } else {
            assertAvailable("A10");
            assertHeldBy("A11", "right-hold");
            assertHeldBy("A12", "right-hold");
        }
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
            putSeat(seat);
        }
    }

    private void putSeat(SeatFixture seat) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(seat.toItem())
                .build());
    }

    private void whenCreateHold(String holdId, String... seatIds) {
        Set<SeatId> seats = seatIds(seatIds);
        SeatPriceQuote quote = repository.quoteSeatPrices(EVENT_ID, seats);
        whenCreateHold(activeHold(holdId, seats, quote.totalPrice()), quote);
    }

    private void whenCreateHold(Hold hold, SeatPriceQuote quote) {
        requestedHold = hold;
        thrown = null;
        try {
            repository.createWithSeatClaims(hold, quote, NOW);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private RaceResult claim(String holdId, Set<SeatId> seats, SeatPriceQuote quote) {
        Hold hold = activeHold(holdId, seats, quote.totalPrice());
        try {
            repository.createWithSeatClaims(hold, quote, NOW);
            return new RaceResult(holdId, null);
        } catch (Throwable error) {
            return new RaceResult(holdId, error);
        }
    }

    private List<RaceResult> race(int contenders, IndexedClaim claim) throws Exception {
        List<Claim> claims = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            int index = i;
            claims.add(() -> claim.run(index));
        }
        return race(claims);
    }

    private List<RaceResult> race(List<Claim> claims) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(claims.size());
        CountDownLatch ready = new CountDownLatch(claims.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RaceResult>> futures = claims.stream()
                    .map(claim -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return claim.run();
                    }))
                    .toList();
            ready.await();
            start.countDown();

            List<RaceResult> results = new ArrayList<>();
            for (Future<RaceResult> future : futures) results.add(future.get());
            return results;
        } finally {
            executor.shutdownNow();
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
        assertAvailable(seatId);
    }

    private void assertHeldBy(String seatId, String holdId) {
        Map<String, AttributeValue> item = seatItem(seatId);
        assertThat(item.get("status").s()).isEqualTo("HELD");
        assertThat(item.get("holdId").s()).isEqualTo(holdId);
    }

    private void assertAvailable(String seatId) {
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

    private static Hold activeHold(String holdId, Set<SeatId> seatIds, Price totalPrice) {
        return Hold.active(
                new HoldId(holdId),
                USER_ID,
                EVENT_ID,
                seatIds,
                totalPrice,
                NOW,
                NOW.plus(5, ChronoUnit.MINUTES));
    }

    private static Set<SeatId> seatIds(String... seatIds) {
        return Set.of(java.util.Arrays.stream(seatIds).map(SeatId::new).toArray(SeatId[]::new));
    }

    private static SeatFixture availableSeat(String seatId, Price price) {
        return new SeatFixture(seatId, "AVAILABLE", null, null, price);
    }

    private static SeatFixture heldSeat(String seatId, String holdId, Instant expiresAt, Price price) {
        return new SeatFixture(seatId, "HELD", holdId, expiresAt, price);
    }

    private static Price price(String amount) {
        return new Price(new BigDecimal(amount), USD);
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

    @FunctionalInterface
    private interface Claim {
        RaceResult run();
    }

    @FunctionalInterface
    private interface IndexedClaim {
        RaceResult run(int index);
    }

    private record RaceResult(String holdId, Throwable error) {
        boolean won() {
            return error == null;
        }
    }

    private record SeatFixture(String seatId, String status, String holdId, Instant expiresAt, Price price) {
        Map<String, AttributeValue> toItem() {
            Map<String, AttributeValue> item = new LinkedHashMap<>();
            item.put("pk", string(seatPk(seatId)));
            item.put("entityType", string("SEAT"));
            item.put("eventId", string(EVENT_ID.value()));
            item.put("seatId", string(seatId));
            item.put("status", string(status));
            item.put("priceAmount", string(price.amount().toPlainString()));
            item.put("priceCurrency", string(price.currency().getCurrencyCode()));
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
