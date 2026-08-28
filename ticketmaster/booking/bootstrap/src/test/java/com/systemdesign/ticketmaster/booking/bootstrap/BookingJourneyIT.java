package com.systemdesign.ticketmaster.booking.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.application.CreateHoldCommand;
import com.systemdesign.ticketmaster.booking.application.CreateHoldHandler;
import com.systemdesign.ticketmaster.booking.application.ReconcileBookingHandler;
import com.systemdesign.ticketmaster.booking.application.StartCheckoutCommand;
import com.systemdesign.ticketmaster.booking.application.StartCheckoutHandler;
import com.systemdesign.ticketmaster.booking.application.StartCheckoutResult;
import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.HoldStatus;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DemoPaymentGateway;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoBookingRepository;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoCheckoutGateway;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoHoldRepository;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoWaitingRoomRepository;
import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class BookingJourneyIT {
    private static final Instant NOW = Instant.parse("2026-08-28T18:00:00Z");
    private static final EventId EVENT_ID = new EventId("event-journey");
    private static final SeatId SEAT_ID = new SeatId("A10");
    private static final UserId USER_ID = new UserId("user-journey");
    private static final EventWriteAuthority LOCAL_OWNER = ignored -> {};

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
    void holdCheckoutSuccessfulPaymentAndReconciliationBooksSeatEndToEnd() {
        initializeDynamo();
        seedAvailableSeat();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DynamoHoldRepository holdRepository = new DynamoHoldRepository(dynamoDb, tableName);
        DynamoBookingRepository bookingRepository = new DynamoBookingRepository(dynamoDb, tableName);
        DynamoCheckoutGateway checkoutGateway = new DynamoCheckoutGateway(dynamoDb, tableName);
        DynamoWaitingRoomRepository waitingRoomRepository = new DynamoWaitingRoomRepository(dynamoDb, tableName);
        DemoPaymentGateway paymentGateway = new DemoPaymentGateway();

        CreateHoldHandler createHold = new CreateHoldHandler(
                LOCAL_OWNER,
                holdRepository,
                waitingRoomRepository,
                clock,
                Duration.ofMinutes(5));
        StartCheckoutHandler startCheckout = new StartCheckoutHandler(
                LOCAL_OWNER,
                holdRepository,
                bookingRepository,
                checkoutGateway,
                paymentGateway,
                clock,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                16);
        ReconcileBookingHandler reconcile = new ReconcileBookingHandler(
                LOCAL_OWNER,
                bookingRepository,
                holdRepository,
                checkoutGateway,
                paymentGateway,
                clock,
                Duration.ofSeconds(30));

        Hold hold = createHold.handle(new CreateHoldCommand(
                USER_ID,
                EVENT_ID,
                List.of(SEAT_ID),
                new HoldIdempotencyKey("journey-hold-key")));
        StartCheckoutResult checkout = startCheckout.handle(new StartCheckoutCommand(
                EVENT_ID,
                hold.id(),
                "journey-checkout-key"));

        paymentGateway.succeedPayment(checkout.booking().id());
        Booking confirmed = reconcile.handle(EVENT_ID, checkout.booking().id());

        assertThat(confirmed.status().name()).isEqualTo("CONFIRMED");
        assertThat(bookingRepository.findById(confirmed.id()).orElseThrow().status().name())
                .isEqualTo("CONFIRMED");
        assertThat(holdRepository.findById(hold.id()).orElseThrow().status())
                .isEqualTo(HoldStatus.CONVERTED);
        Map<String, AttributeValue> seat = seatItem();
        assertThat(seat.get("status").s()).isEqualTo("BOOKED");
        assertThat(seat.get("bookingId").s()).isEqualTo(confirmed.id().value());
        assertThat(seat).doesNotContainKey("holdExpiresAt");
    }

    private void initializeDynamo() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-journey-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("reconcileShard").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("nextReconcileAt").attributeType(ScalarAttributeType.N).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("reconciliation-index")
                        .keySchema(
                                KeySchemaElement.builder().attributeName("reconcileShard").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("nextReconcileAt").keyType(KeyType.RANGE).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .build());
    }

    private void seedAvailableSeat() {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", string(seatPk()),
                        "entityType", string("SEAT"),
                        "eventId", string(EVENT_ID.value()),
                        "seatId", string(SEAT_ID.value()),
                        "sectionId", string("101"),
                        "row", string("A"),
                        "number", string("10"),
                        "status", string("AVAILABLE"),
                        "priceAmount", string("125.00"),
                        "priceCurrency", string("USD")))
                .build());
    }

    private Map<String, AttributeValue> seatItem() {
        return dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", string(seatPk())))
                        .consistentRead(true)
                        .build())
                .item();
    }

    private static String seatPk() {
        return "EVENT#" + EVENT_ID.value() + "#SEAT#" + SEAT_ID.value();
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
