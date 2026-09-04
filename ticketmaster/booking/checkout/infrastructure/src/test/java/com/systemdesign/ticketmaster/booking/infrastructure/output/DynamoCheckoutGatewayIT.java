package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.CheckoutConflictException;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldStatus;
import com.systemdesign.ticketmaster.booking.domain.PreparedCheckout;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import io.floci.testcontainers.FlociContainer;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashMap;
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
class DynamoCheckoutGatewayIT {
    private static final Instant NOW = Instant.parse("2026-08-27T22:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(600);
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final Price SEAT_PRICE = price("100.00");

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoHoldRepository holdRepository;
    private DynamoBookingRepository bookingRepository;
    private DynamoCheckoutGateway checkoutGateway;
    private String tableName;
    private PreparedCheckout preparedCheckout;
    private Booking pendingBooking;

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void nextAtomicallyClaimsAvailableSeatsAndCreatesReservationBookingAndIdempotency() {
        givenPreparedCheckout("A10", "A11");

        checkoutGateway.startCheckout(preparedCheckout, pendingBooking);

        assertThat(holdRepository.findById(preparedCheckout.reservation().id()).orElseThrow().status())
                .isEqualTo(HoldStatus.CHECKOUT_IN_PROGRESS);
        assertThat(bookingRepository.findById(pendingBooking.id())).contains(pendingBooking);
        assertThat(bookingRepository.findByCheckoutIdempotencyKey(EVENT_ID, USER_ID, "idempotency-1"))
                .contains(pendingBooking);
        assertThat(bookingRepository.findDueForReconciliation(3, NOW.plusSeconds(30), 10))
                .containsExactly(pendingBooking);
        for (String seatId : List.of("A10", "A11")) {
            Map<String, AttributeValue> item = seatItem(seatId);
            assertThat(item.get("status").s()).isEqualTo("CHECKOUT");
            assertThat(item.get("holdId").s()).isEqualTo(preparedCheckout.reservation().id().value());
            assertThat(Long.parseLong(item.get("checkoutExpiresAt").n())).isEqualTo(DEADLINE.toEpochMilli());
            assertThat(item).doesNotContainKey("holdExpiresAt");
        }
    }

    @Test
    void priceChangeBetweenQuoteAndClaimRejectsWholeCheckout() {
        givenPreparedCheckout("A10", "A11");
        changeSeatPrice("A11", price("150.00"));

        Throwable thrown = capture(() -> checkoutGateway.startCheckout(preparedCheckout, pendingBooking));

        assertThat(thrown).isInstanceOf(CheckoutConflictException.class);
        assertThat(holdRepository.findById(preparedCheckout.reservation().id())).isEmpty();
        assertThat(bookingRepository.findById(pendingBooking.id())).isEmpty();
        assertThat(seatItem("A10").get("status").s()).isEqualTo("AVAILABLE");
        assertThat(seatItem("A11").get("status").s()).isEqualTo("AVAILABLE");
    }

    @Test
    void confirmedPaymentBooksSeatsAndRemovesReconciliationWork() {
        givenStartedCheckout("A10", "A11");
        Booking withIntent = pendingBooking.attachPaymentIntent("pi-123");
        bookingRepository.savePaymentIntent(withIntent);

        checkoutGateway.finalizeBooking(preparedCheckout.reservation(), withIntent.confirm());

        assertThat(bookingRepository.findById(pendingBooking.id()).orElseThrow().status().name()).isEqualTo("CONFIRMED");
        assertThat(bookingRepository.findDueForReconciliation(3, DEADLINE.plusSeconds(1), 10)).isEmpty();
        assertThat(holdRepository.findById(preparedCheckout.reservation().id()).orElseThrow().status())
                .isEqualTo(HoldStatus.CONVERTED);
        for (String seatId : List.of("A10", "A11")) {
            Map<String, AttributeValue> item = seatItem(seatId);
            assertThat(item.get("status").s()).isEqualTo("BOOKED");
            assertThat(item.get("bookingId").s()).isEqualTo(pendingBooking.id().value());
            assertThat(item).doesNotContainKeys("checkoutExpiresAt", "holdExpiresAt");
        }
    }

    @Test
    void canceledPaymentReleasesSeats() {
        givenStartedCheckout("A10", "A11");
        Booking withIntent = pendingBooking.attachPaymentIntent("pi-123");
        bookingRepository.savePaymentIntent(withIntent);

        checkoutGateway.failBooking(preparedCheckout.reservation(), withIntent.fail());

        assertThat(holdRepository.findById(preparedCheckout.reservation().id()).orElseThrow().status())
                .isEqualTo(HoldStatus.FAILED);
        for (String seatId : List.of("A10", "A11")) {
            Map<String, AttributeValue> item = seatItem(seatId);
            assertThat(item.get("status").s()).isEqualTo("AVAILABLE");
            assertThat(item).doesNotContainKeys("holdId", "checkoutExpiresAt", "holdExpiresAt", "bookingId");
        }
    }

    @Test
    void expiredCheckoutCannotBeBlindlyReclaimedByAnotherCheckout() {
        givenStartedCheckout("A10");
        PreparedCheckout later = prepared(
                new HoldId("checkout-later"), new UserId("user-other"), NOW.plusSeconds(700), "A10");
        Booking laterBooking = Booking.pending(
                new BookingId("booking-later"), later.reservation(), "later-key",
                NOW.plusSeconds(700), NOW.plusSeconds(730), 4);

        Throwable thrown = capture(() -> checkoutGateway.startCheckout(later, laterBooking));

        assertThat(thrown).isInstanceOf(CheckoutConflictException.class);
        assertThat(seatItem("A10").get("status").s()).isEqualTo("CHECKOUT");
        assertThat(seatItem("A10").get("holdId").s())
                .isEqualTo(preparedCheckout.reservation().id().value());
    }

    private void givenPreparedCheckout(String... seatIds) {
        initializeDynamo();
        for (String seatId : seatIds) putAvailableSeat(seatId);
        preparedCheckout = prepared(new HoldId("checkout-1"), USER_ID, DEADLINE, seatIds);
        pendingBooking = Booking.pending(
                new BookingId("booking-1"), preparedCheckout.reservation(), "idempotency-1",
                NOW, NOW.plusSeconds(30), 3);
    }

    private void givenStartedCheckout(String... seatIds) {
        givenPreparedCheckout(seatIds);
        checkoutGateway.startCheckout(preparedCheckout, pendingBooking);
    }

    private PreparedCheckout prepared(HoldId id, UserId userId, Instant deadline, String... seatIds) {
        Set<SeatId> seats = seatIds(seatIds);
        Map<SeatId, Price> prices = new HashMap<>();
        for (SeatId seat : seats) prices.put(seat, SEAT_PRICE);
        Price total = new Price(
                SEAT_PRICE.amount().multiply(BigDecimal.valueOf(seats.size())), SEAT_PRICE.currency());
        ReservationCheckout reservation = new ReservationCheckout(
                id, userId, EVENT_ID, seats, total,
                ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS, deadline);
        return new PreparedCheckout(reservation, prices);
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

    private void changeSeatPrice(String seatId, Price price) {
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("pk", string(DynamoKeys.seatPk(EVENT_ID, new SeatId(seatId)))))
                .updateExpression("SET #amount = :amount, #currency = :currency")
                .expressionAttributeNames(Map.of("#amount", "priceAmount", "#currency", "priceCurrency"))
                .expressionAttributeValues(Map.of(
                        ":amount", string(price.amount().toPlainString()),
                        ":currency", string(price.currency().getCurrencyCode())))
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
        return Set.of(Arrays.stream(seatIds).map(SeatId::new).toArray(SeatId[]::new));
    }

    private static Price price(String amount) {
        return new Price(new BigDecimal(amount), Currency.getInstance("USD"));
    }

    private static Throwable capture(Operation operation) {
        try { operation.run(); return null; } catch (Throwable thrown) { return thrown; }
    }

    @FunctionalInterface
    private interface Operation { void run(); }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
