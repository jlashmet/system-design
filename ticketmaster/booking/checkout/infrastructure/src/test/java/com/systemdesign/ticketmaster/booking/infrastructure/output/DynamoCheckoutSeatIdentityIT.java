package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.CheckoutConflictException;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.PreparedCheckout;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import io.floci.testcontainers.FlociContainer;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
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
class DynamoCheckoutSeatIdentityIT {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final SeatId SEAT_ID = new SeatId("A10");
    private static final HoldId HOLD_ID = new HoldId("hold-1");
    private static final Instant NOW = Instant.parse("2026-08-27T22:00:00Z");
    private static final Price PRICE = new Price(new BigDecimal("100.00"), Currency.getInstance("USD"));

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private String tableName;
    private DynamoCheckoutGateway gateway;
    private Hold checkoutHold;
    private ReservationCheckout checkoutReservation;
    private PreparedCheckout preparedCheckout;
    private Booking pendingBooking;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-checkout-seat-identity-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder()
                        .attributeName("pk").keyType(KeyType.HASH).build())
                .build());
        gateway = new DynamoCheckoutGateway(dynamoDb, tableName);
        checkoutHold = Hold.checkout(
                HOLD_ID, USER_ID, EVENT_ID, Set.of(SEAT_ID), PRICE,
                NOW.plusSeconds(10), NOW.plusSeconds(120));
        checkoutReservation = ReservationTestFixtures.from(checkoutHold);
        preparedCheckout = new PreparedCheckout(checkoutReservation, Map.of(SEAT_ID, PRICE));
        pendingBooking = Booking.pending(
                        new BookingId("booking-1"), checkoutReservation, "checkout-key", NOW.plusSeconds(10),
                        NOW.plusSeconds(40), 1)
                .attachPaymentIntent("pi-1");
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void startCheckoutRejectsMismatchedSeatIdentityWithoutMutatingTransaction() {
        putAvailableSeat("other-event");
        Booking pendingWithoutIntent = Booking.pending(
                pendingBooking.id(), checkoutReservation, pendingBooking.checkoutIdempotencyKey(),
                pendingBooking.createdAt(), pendingBooking.nextReconcileAt(), pendingBooking.reconcileShard());

        assertThatThrownBy(() -> gateway.startCheckout(preparedCheckout, pendingWithoutIntent))
                .isInstanceOf(CheckoutConflictException.class);

        assertThat(holdItem()).isEmpty();
        assertThat(seatItem().get("status").s()).isEqualTo("AVAILABLE");
        assertThat(seatItem().get("eventId").s()).isEqualTo("other-event");
        assertThat(bookingItem()).isEmpty();
    }

    @Test
    void finalizeRejectsMismatchedSeatIdentityWithoutMutatingTransaction() {
        givenPendingCheckoutWithCorruptSeat();

        assertThatThrownBy(() -> gateway.finalizeBooking(checkoutReservation, pendingBooking.confirm()))
                .isInstanceOf(CheckoutConflictException.class);

        assertPendingCheckoutUnchanged();
        assertThat(seatItem()).doesNotContainKey("bookingId");
    }

    @Test
    void failureRejectsMismatchedSeatIdentityWithoutMutatingTransaction() {
        givenPendingCheckoutWithCorruptSeat();

        assertThatThrownBy(() -> gateway.failBooking(checkoutReservation, pendingBooking.fail()))
                .isInstanceOf(CheckoutConflictException.class);

        assertPendingCheckoutUnchanged();
        assertThat(seatItem().get("holdId").s()).isEqualTo(HOLD_ID.value());
    }

    private void givenPendingCheckoutWithCorruptSeat() {
        putHold(checkoutHold);
        putBooking(pendingBooking);
        putCheckoutSeat("other-event", HOLD_ID.value(), checkoutHold.checkoutExpiresAt().toEpochMilli());
    }

    private void assertPendingCheckoutUnchanged() {
        assertThat(holdItem().get("status").s()).isEqualTo("CHECKOUT_IN_PROGRESS");
        assertThat(bookingItem().get("status").s()).isEqualTo("PENDING_PAYMENT");
        assertThat(seatItem().get("status").s()).isEqualTo("CHECKOUT");
        assertThat(seatItem().get("eventId").s()).isEqualTo("other-event");
    }

    private void putHold(Hold hold) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", string(DynamoKeys.holdPk(hold.id())));
        item.put("entityType", string("HOLD"));
        item.put("holdId", string(hold.id().value()));
        item.put("userId", string(hold.userId().value()));
        item.put("eventId", string(hold.eventId().value()));
        item.put("seatIds", AttributeValue.builder().ss(SEAT_ID.value()).build());
        item.put("totalPriceAmount", string(PRICE.amount().toPlainString()));
        item.put("totalPriceCurrency", string(PRICE.currency().getCurrencyCode()));
        item.put("status", string("CHECKOUT_IN_PROGRESS"));
        item.put("checkoutExpiresAt", number(hold.checkoutExpiresAt().toEpochMilli()));
        item.put("createdAt", number(hold.createdAt().toEpochMilli()));
        dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    private void putBooking(Booking booking) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(DynamoItemCodec.bookingToItem(booking))
                .build());
    }

    private void putAvailableSeat(String eventId) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", string(DynamoKeys.seatPk(EVENT_ID, SEAT_ID)),
                        "entityType", string("SEAT"),
                        "eventId", string(eventId),
                        "seatId", string(SEAT_ID.value()),
                        "status", string("AVAILABLE"),
                        "priceAmount", string(PRICE.amount().toPlainString()),
                        "priceCurrency", string(PRICE.currency().getCurrencyCode())))
                .build());
    }

    private void putCheckoutSeat(String eventId, String holdId, long checkoutExpiresAt) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.ofEntries(
                        Map.entry("pk", string(DynamoKeys.seatPk(EVENT_ID, SEAT_ID))),
                        Map.entry("entityType", string("SEAT")),
                        Map.entry("eventId", string(eventId)),
                        Map.entry("seatId", string(SEAT_ID.value())),
                        Map.entry("status", string("CHECKOUT")),
                        Map.entry("holdId", string(holdId)),
                        Map.entry("checkoutExpiresAt", number(checkoutExpiresAt)),
                        Map.entry("priceAmount", string(PRICE.amount().toPlainString())),
                        Map.entry("priceCurrency", string(PRICE.currency().getCurrencyCode()))))
                .build());
    }

    private Map<String, AttributeValue> holdItem() {
        return get(DynamoKeys.holdPk(HOLD_ID));
    }

    private Map<String, AttributeValue> seatItem() {
        return get(DynamoKeys.seatPk(EVENT_ID, SEAT_ID));
    }

    private Map<String, AttributeValue> bookingItem() {
        return get(DynamoKeys.bookingPk(pendingBooking.id()));
    }

    private Map<String, AttributeValue> get(String pk) {
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", string(pk)))
                        .consistentRead(true)
                        .build())
                .item();
        return item == null ? Map.of() : item;
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
