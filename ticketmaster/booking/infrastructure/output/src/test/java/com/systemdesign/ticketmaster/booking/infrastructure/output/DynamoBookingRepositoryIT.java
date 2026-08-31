package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import io.floci.testcontainers.FlociContainer;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
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
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class DynamoBookingRepositoryIT {
    private static final Instant FIRST_DUE = Instant.parse("2026-08-27T22:00:00Z");
    private static final Instant SECOND_DUE = FIRST_DUE.plusSeconds(60);
    private static final Instant THIRD_DUE = SECOND_DUE.plusSeconds(60);

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoBookingRepository repository;
    private String tableName;
    private Booking booking;
    private Booking rescheduled;
    private Booking newest;
    private Booking stale;
    private Booking confirmed;
    private Throwable thrown;

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void reschedulingMovesBookingOutOfCurrentDueWindow() {
        givenPendingBooking();
        whenRescheduleTo(SECOND_DUE);
        thenExpectOnlyDueAtNewTime();
    }

    @Test
    void staleReconciliationRescheduleDoesNotMoveBookingBackward() {
        givenPendingBooking();
        whenNewerThenStaleRescheduleRace();
        thenExpectNewestSchedulePreserved();
    }

    @Test
    void savingSamePaymentIntentAfterConcurrentFinalizationIsIdempotent() {
        givenConfirmedBookingWithPaymentIntent();
        whenStaleWorkerSavesSamePaymentIntent();
        thenExpectConfirmedBookingPreserved();
    }

    @Test
    void directLookupRejectsBookingWhoseStoredIdDisagreesWithPrimaryKey() {
        initialize();
        BookingId requestedId = new BookingId("booking-1");
        Hold hold = Hold.active(new HoldId("hold-1"), new UserId("user-1"), new EventId("event-1"),
                Set.of(new SeatId("A10")), new Price(new BigDecimal("100.00"), Currency.getInstance("USD")),
                FIRST_DUE.minusSeconds(300), FIRST_DUE.plusSeconds(300));
        Booking booking = Booking.pending(requestedId,
                hold.startCheckout(FIRST_DUE.minusSeconds(30), FIRST_DUE.plusSeconds(90)),
                "checkout-1", FIRST_DUE.minusSeconds(30), FIRST_DUE, 2);
        Map<String, AttributeValue> corruptBooking = new HashMap<>(DynamoItemCodec.bookingToItem(booking));
        corruptBooking.put("bookingId", DynamoItemCodec.string("different-booking-id"));
        putRaw(corruptBooking);

        assertThatThrownBy(() -> repository.findById(requestedId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("booking record identity mismatch for booking-1");
    }

    @Test
    void checkoutIdempotencyLookupFailsClosedWhenMappingIsMissingBookingId() {
        initialize();
        EventId eventId = new EventId("event-1");
        HoldId holdId = new HoldId("hold-1");
        String idempotencyKey = "checkout-1";
        putRaw(Map.of(
                "pk", DynamoItemCodec.string(DynamoKeys.idempotencyPk(eventId, holdId, idempotencyKey)),
                "entityType", DynamoItemCodec.string("CHECKOUT_IDEMPOTENCY"),
                "eventId", DynamoItemCodec.string(eventId.value()),
                "holdId", DynamoItemCodec.string(holdId.value()),
                "idempotencyKey", DynamoItemCodec.string(idempotencyKey)));

        assertThatThrownBy(() -> repository.findByCheckoutIdempotencyKey(eventId, holdId, idempotencyKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("checkout idempotency record is missing bookingId");
    }

    @Test
    void checkoutIdempotencyLookupFailsClosedWhenMappingReferencesMissingBooking() {
        initialize();
        EventId eventId = new EventId("event-1");
        HoldId holdId = new HoldId("hold-1");
        String idempotencyKey = "checkout-1";
        putRaw(Map.of(
                "pk", DynamoItemCodec.string(DynamoKeys.idempotencyPk(eventId, holdId, idempotencyKey)),
                "entityType", DynamoItemCodec.string("CHECKOUT_IDEMPOTENCY"),
                "eventId", DynamoItemCodec.string(eventId.value()),
                "holdId", DynamoItemCodec.string(holdId.value()),
                "idempotencyKey", DynamoItemCodec.string(idempotencyKey),
                "bookingId", DynamoItemCodec.string("missing-booking")));

        assertThatThrownBy(() -> repository.findByCheckoutIdempotencyKey(eventId, holdId, idempotencyKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("checkout idempotency record references missing booking missing-booking");
    }

    @Test
    void checkoutIdempotencyLookupRejectsReferencedBookingOutsideMappingScope() {
        initialize();
        EventId eventId = new EventId("event-1");
        HoldId holdId = new HoldId("hold-1");
        String idempotencyKey = "checkout-1";
        Hold hold = Hold.active(holdId, new UserId("user-1"), eventId,
                Set.of(new SeatId("A10")), new Price(new BigDecimal("100.00"), Currency.getInstance("USD")),
                FIRST_DUE.minusSeconds(300), FIRST_DUE.plusSeconds(300));
        Booking wrongKeyBooking = Booking.pending(new BookingId("booking-1"),
                hold.startCheckout(FIRST_DUE.minusSeconds(30), FIRST_DUE.plusSeconds(90)),
                "different-checkout-key", FIRST_DUE.minusSeconds(30), FIRST_DUE, 2);
        put(wrongKeyBooking);
        putRaw(Map.of(
                "pk", DynamoItemCodec.string(DynamoKeys.idempotencyPk(eventId, holdId, idempotencyKey)),
                "entityType", DynamoItemCodec.string("CHECKOUT_IDEMPOTENCY"),
                "eventId", DynamoItemCodec.string(eventId.value()),
                "holdId", DynamoItemCodec.string(holdId.value()),
                "idempotencyKey", DynamoItemCodec.string(idempotencyKey),
                "bookingId", DynamoItemCodec.string(wrongKeyBooking.id().value())));

        assertThatThrownBy(() -> repository.findByCheckoutIdempotencyKey(eventId, holdId, idempotencyKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("checkout idempotency record resolved outside its booking/event/hold/key scope");
    }

    @Test
    void checkoutIdempotencyLookupRejectsBookingWhoseStoredIdDisagreesWithPrimaryKey() {
        initialize();
        EventId eventId = new EventId("event-1");
        HoldId holdId = new HoldId("hold-1");
        String idempotencyKey = "checkout-1";
        Hold hold = Hold.active(holdId, new UserId("user-1"), eventId,
                Set.of(new SeatId("A10")), new Price(new BigDecimal("100.00"), Currency.getInstance("USD")),
                FIRST_DUE.minusSeconds(300), FIRST_DUE.plusSeconds(300));
        Booking booking = Booking.pending(new BookingId("booking-1"),
                hold.startCheckout(FIRST_DUE.minusSeconds(30), FIRST_DUE.plusSeconds(90)),
                idempotencyKey, FIRST_DUE.minusSeconds(30), FIRST_DUE, 2);
        Map<String, AttributeValue> corruptBooking = new HashMap<>(DynamoItemCodec.bookingToItem(booking));
        corruptBooking.put("bookingId", DynamoItemCodec.string("different-booking-id"));
        putRaw(corruptBooking);
        putRaw(DynamoItemCodec.idempotencyItem(booking));

        assertThatThrownBy(() -> repository.findByCheckoutIdempotencyKey(eventId, holdId, idempotencyKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("booking record identity mismatch for booking-1");
    }

    private void givenPendingBooking() {
        initialize();
        Hold hold = Hold.active(new HoldId("hold-1"), new UserId("user-1"), new EventId("event-1"),
                Set.of(new SeatId("A10")), new Price(new BigDecimal("100.00"), Currency.getInstance("USD")),
                FIRST_DUE.minusSeconds(300), FIRST_DUE.plusSeconds(300));
        booking = Booking.pending(new BookingId("booking-1"), hold.startCheckout(FIRST_DUE.minusSeconds(30), FIRST_DUE.plusSeconds(90)),
                "checkout-1", FIRST_DUE.minusSeconds(30), FIRST_DUE, 2).attachPaymentIntent("pi-1");
        put(booking);
        rescheduled = null;
        newest = null;
        stale = null;
        confirmed = null;
        thrown = null;
    }

    private void givenConfirmedBookingWithPaymentIntent() {
        givenPendingBooking();
        confirmed = booking.confirm();
        put(confirmed);
    }

    private void whenRescheduleTo(Instant nextAttempt) {
        rescheduled = booking.rescheduleReconciliation(nextAttempt);
        repository.rescheduleReconciliation(rescheduled);
    }

    private void whenNewerThenStaleRescheduleRace() {
        newest = booking.rescheduleReconciliation(THIRD_DUE);
        repository.rescheduleReconciliation(newest);
        stale = booking.rescheduleReconciliation(SECOND_DUE);
        repository.rescheduleReconciliation(stale);
    }

    private void whenStaleWorkerSavesSamePaymentIntent() {
        try {
            repository.savePaymentIntent(booking);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectOnlyDueAtNewTime() {
        assertThat(repository.findDueForReconciliation(2, FIRST_DUE, 10)).isEmpty();
        assertThat(repository.findDueForReconciliation(2, SECOND_DUE, 10)).containsExactly(rescheduled);
        assertThat(repository.findById(booking.id())).contains(rescheduled);
    }

    private void thenExpectNewestSchedulePreserved() {
        assertThat(repository.findById(booking.id())).contains(newest);
        assertThat(repository.findDueForReconciliation(2, SECOND_DUE, 10)).isEmpty();
        assertThat(repository.findDueForReconciliation(2, THIRD_DUE, 10)).containsExactly(newest);
    }

    private void thenExpectConfirmedBookingPreserved() {
        assertThatCode(() -> {
            if (thrown != null) throw thrown;
        }).doesNotThrowAnyException();
        assertThat(repository.findById(booking.id())).contains(confirmed);
    }

    private void put(Booking value) {
        putRaw(DynamoItemCodec.bookingToItem(value));
    }

    private void putRaw(Map<String, AttributeValue> item) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());
    }

    private void initialize() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-reconciliation-" + UUID.randomUUID();
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
        repository = new DynamoBookingRepository(dynamoDb, tableName);
    }
}
