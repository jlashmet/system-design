package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldStatus;
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

@Testcontainers
class DynamoCheckoutGatewayIT {
    private static final Instant NOW = Instant.parse("2026-08-27T22:00:00Z");
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final Price SEAT_PRICE = new Price(new BigDecimal("100.00"), Currency.getInstance("USD"));

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoHoldRepository holdRepository;
    private DynamoBookingRepository bookingRepository;
    private DynamoCheckoutGateway checkoutGateway;
    private String tableName;
    private Hold activeHold;
    private Hold checkoutHold;
    private Booking pendingBooking;
    private Hold laterHold;
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
    void startsCheckoutAtomicallyAndSchedulesReconciliation() {
        givenActiveHold("A10", "A11");
        whenStartCheckout();
        thenExpectCheckoutStarted("A10", "A11");
    }

    @Test
    void confirmedPaymentBooksSeatsAndRemovesReconciliationWork() {
        givenStartedCheckout("A10", "A11");
        whenFinalizeBooking();
        thenExpectConfirmed("A10", "A11");
    }

    @Test
    void failedPaymentReleasesSeatsAndRemovesReconciliationWork() {
        givenStartedCheckout("A10", "A11");
        whenFailBooking();
        thenExpectFailedAndReleased("A10", "A11");
    }

    @Test
    void checkoutSeatCannotBeBlindlyReclaimedAfterCheckoutDeadline() {
        givenStartedCheckout("A10");
        whenAttemptNewHoldAfterCheckoutDeadline();
        thenExpectSeatClaimConflictAndOriginalCheckout("A10");
    }

    private void givenActiveHold(String... seatIds) {
        initializeDynamo();
        for (String seatId : seatIds) putAvailableSeat(seatId);
        Set<SeatId> requestedSeats = seatIds(seatIds);
        SeatPriceQuote quote = holdRepository.quoteSeatPrices(EVENT_ID, requestedSeats);
        activeHold = Hold.active(new HoldId("hold-1"), USER_ID, EVENT_ID, requestedSeats, quote.totalPrice(),
                NOW, NOW.plus(5, ChronoUnit.MINUTES));
        holdRepository.createWithSeatClaims(activeHold, quote, NOW);
        checkoutHold = activeHold.startCheckout(NOW.plusSeconds(10), NOW.plus(70, ChronoUnit.SECONDS));
        pendingBooking = Booking.pending(new BookingId("booking-1"), checkoutHold, "idempotency-1",
                NOW.plusSeconds(10), NOW.plusSeconds(40), 3);
        thrown = null;
    }

    private void givenStartedCheckout(String... seatIds) {
        givenActiveHold(seatIds);
        checkoutGateway.startCheckout(checkoutHold, pendingBooking);
        Booking withIntent = pendingBooking.attachPaymentIntent("pi-123");
        bookingRepository.savePaymentIntent(withIntent);
        pendingBooking = withIntent;
    }

    private void whenStartCheckout() {
        try {
            checkoutGateway.startCheckout(checkoutHold, pendingBooking);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void whenFinalizeBooking() {
        try {
            checkoutGateway.finalizeBooking(checkoutHold.convert(), pendingBooking.confirm());
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void whenFailBooking() {
        try {
            checkoutGateway.failBooking(checkoutHold.fail(), pendingBooking.fail());
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void whenAttemptNewHoldAfterCheckoutDeadline() {
        Set<SeatId> requestedSeats = Set.of(new SeatId("A10"));
        SeatPriceQuote quote = holdRepository.quoteSeatPrices(EVENT_ID, requestedSeats);
        laterHold = Hold.active(new HoldId("hold-2"), new UserId("user-789"), EVENT_ID,
                requestedSeats, quote.totalPrice(), NOW.plusSeconds(80), NOW.plusSeconds(380));
        try {
            holdRepository.createWithSeatClaims(laterHold, quote, NOW.plusSeconds(80));
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectCheckoutStarted(String... seatIds) {
        assertThat(thrown).isNull();
        assertThat(holdRepository.findById(activeHold.id()).orElseThrow().status())
                .isEqualTo(HoldStatus.CHECKOUT_IN_PROGRESS);
        assertThat(bookingRepository.findById(pendingBooking.id())).contains(pendingBooking);
        assertThat(bookingRepository.findByCheckoutIdempotencyKey(
                EVENT_ID, activeHold.id(), "idempotency-1")).contains(pendingBooking);
        assertThat(bookingRepository.findByCheckoutIdempotencyKey(
                EVENT_ID, new HoldId("other-hold"), "idempotency-1")).isEmpty();
        assertThat(bookingRepository.findDueForReconciliation(3, NOW.plusSeconds(40), 10))
                .containsExactly(pendingBooking);
        for (String seatId : seatIds) {
            Map<String, AttributeValue> item = seatItem(seatId);
            assertThat(item.get("status").s()).isEqualTo("CHECKOUT");
            assertThat(item.get("holdId").s()).isEqualTo(activeHold.id().value());
            assertThat(Long.parseLong(item.get("holdExpiresAt").n()))
                    .isEqualTo(checkoutHold.checkoutExpiresAt().toEpochMilli());
        }
    }

    private void thenExpectConfirmed(String... seatIds) {
        assertThat(thrown).isNull();
        Booking stored = bookingRepository.findById(pendingBooking.id()).orElseThrow();
        assertThat(stored.status().name()).isEqualTo("CONFIRMED");
        assertThat(stored.paymentIntentId()).isEqualTo("pi-123");
        assertThat(bookingRepository.findDueForReconciliation(3, NOW.plusSeconds(500), 10)).isEmpty();
        assertThat(holdRepository.findById(activeHold.id()).orElseThrow().status()).isEqualTo(HoldStatus.CONVERTED);
        for (String seatId : seatIds) {
            Map<String, AttributeValue> item = seatItem(seatId);
            assertThat(item.get("status").s()).isEqualTo("BOOKED");
            assertThat(item.get("bookingId").s()).isEqualTo(pendingBooking.id().value());
            assertThat(item).doesNotContainKey("holdExpiresAt");
        }
    }

    private void thenExpectFailedAndReleased(String... seatIds) {
        assertThat(thrown).isNull();
        assertThat(bookingRepository.findById(pendingBooking.id()).orElseThrow().status().name()).isEqualTo("FAILED");
        assertThat(bookingRepository.findDueForReconciliation(3, NOW.plusSeconds(500), 10)).isEmpty();
        assertThat(holdRepository.findById(activeHold.id()).orElseThrow().status()).isEqualTo(HoldStatus.FAILED);
        for (String seatId : seatIds) {
            Map<String, AttributeValue> item = seatItem(seatId);
            assertThat(item.get("status").s()).isEqualTo("AVAILABLE");
            assertThat(item).doesNotContainKeys("holdId", "holdExpiresAt", "bookingId");
        }
    }

    private void thenExpectSeatClaimConflictAndOriginalCheckout(String seatId) {
        assertThat(thrown).isInstanceOf(SeatClaimConflictException.class);
        assertThat(holdRepository.findById(laterHold.id())).isEmpty();
        Map<String, AttributeValue> item = seatItem(seatId);
        assertThat(item.get("status").s()).isEqualTo("CHECKOUT");
        assertThat(item.get("holdId").s()).isEqualTo(activeHold.id().value());
    }

    private void initializeDynamo() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-checkout-" + UUID.randomUUID();
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
        holdRepository = new DynamoHoldRepository(dynamoDb, tableName);
        bookingRepository = new DynamoBookingRepository(dynamoDb, tableName);
        checkoutGateway = new DynamoCheckoutGateway(dynamoDb, tableName);
    }

    private void putAvailableSeat(String seatId) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", string(DynamoKeys.seatPk(EVENT_ID, new SeatId(seatId))),
                        "entityType", string("SEAT"),
                        "eventId", string(EVENT_ID.value()),
                        "seatId", string(seatId),
                        "status", string("AVAILABLE"),
                        "priceAmount", string(SEAT_PRICE.amount().toPlainString()),
                        "priceCurrency", string(SEAT_PRICE.currency().getCurrencyCode())))
                .build());
    }

    private Map<String, AttributeValue> seatItem(String seatId) {
        return dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", string(DynamoKeys.seatPk(EVENT_ID, new SeatId(seatId)))))
                        .consistentRead(true)
                        .build())
                .item();
    }

    private static Set<SeatId> seatIds(String... seatIds) {
        return Set.of(java.util.Arrays.stream(seatIds).map(SeatId::new).toArray(SeatId[]::new));
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
