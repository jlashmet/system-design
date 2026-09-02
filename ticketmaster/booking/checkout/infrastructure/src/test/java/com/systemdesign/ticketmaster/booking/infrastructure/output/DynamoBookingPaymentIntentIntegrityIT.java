package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingStatus;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import io.floci.testcontainers.FlociContainer;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Currency;
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
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class DynamoBookingPaymentIntentIntegrityIT {
    private static final Instant NOW = Instant.parse("2026-08-31T17:00:00Z");
    private static final Price PRICE = new Price(new BigDecimal("100.00"), Currency.getInstance("USD"));

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
    void paymentIntentWriteRejectsSameBookingIdFromDifferentImmutableScope() {
        DynamoBookingRepository repository = initialize();
        Booking stored = booking(new EventId("event-1"), new UserId("user-1"), PRICE, null);
        put(stored);
        Booking wrongScope = booking(new EventId("event-2"), new UserId("user-1"), PRICE, "pi-wrong-event");

        assertThatThrownBy(() -> repository.savePaymentIntent(wrongScope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("booking payment intent state changed or scope mismatched: booking-1");

        assertThat(repository.findById(stored.id())).contains(stored);
    }

    @Test
    void paymentIntentWriteStillAcceptsMatchingImmutableScope() {
        DynamoBookingRepository repository = initialize();
        Booking stored = booking(new EventId("event-1"), new UserId("user-1"), PRICE, null);
        put(stored);
        Booking withIntent = booking(new EventId("event-1"), new UserId("user-1"), PRICE, "pi-1");

        repository.savePaymentIntent(withIntent);

        assertThat(repository.findById(stored.id()))
                .hasValueSatisfying(saved -> assertThat(saved.paymentIntentId()).isEqualTo("pi-1"));
    }

    private DynamoBookingRepository initialize() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-payment-intent-integrity-" + UUID.randomUUID();
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
        return new DynamoBookingRepository(dynamoDb, tableName);
    }

    private Booking booking(EventId eventId, UserId userId, Price price, String paymentIntentId) {
        return new Booking(
                new BookingId("booking-1"),
                userId,
                eventId,
                new HoldId("hold-1"),
                BookingStatus.PENDING_PAYMENT,
                price,
                "checkout-1",
                paymentIntentId,
                NOW.plusSeconds(60),
                1,
                NOW);
    }

    private void put(Booking booking) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(DynamoItemCodec.bookingToItem(booking))
                .build());
    }
}
