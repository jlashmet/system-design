package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SeatStatus;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import io.floci.testcontainers.FlociContainer;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Currency;
import java.util.List;
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
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class DynamoSeatMapRepositoryIT {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final SectionId SECTION_101 = new SectionId("101");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoSeatMapRepository repository;
    private String tableName;
    private List<SeatMapSeat> actual;

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void sectionReadModelReflectsLatestProjectedSeatState() {
        givenProjectedSeats(
                seat("A10", "A", "10", SeatStatus.AVAILABLE),
                seat("A11", "A", "11", SeatStatus.AVAILABLE));
        whenProjectAndRead(seat("A10", "A", "10", SeatStatus.HELD));
        thenExpectSection(
                seat("A10", "A", "10", SeatStatus.HELD),
                seat("A11", "A", "11", SeatStatus.AVAILABLE));
    }

    private void givenProjectedSeats(SeatMapSeat... seats) {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-seat-map-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .build());
        repository = new DynamoSeatMapRepository(dynamoDb, tableName);
        for (SeatMapSeat seat : seats) repository.upsert(seat);
        actual = null;
    }

    private void whenProjectAndRead(SeatMapSeat updated) {
        repository.upsert(updated);
        actual = repository.findSection(EVENT_ID, SECTION_101);
    }

    private void thenExpectSection(SeatMapSeat... expected) {
        assertThat(actual).containsExactly(expected);
    }

    private static SeatMapSeat seat(String seatId, String row, String number, SeatStatus status) {
        return new SeatMapSeat(EVENT_ID, SECTION_101, new SeatId(seatId), row, number, PRICE, status);
    }
}
