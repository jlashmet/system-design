package com.systemdesign.ticketmaster.events.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.events.domain.Event;
import com.systemdesign.ticketmaster.events.domain.EventId;
import com.systemdesign.ticketmaster.events.domain.EventStatus;
import com.systemdesign.ticketmaster.events.domain.VenueId;
import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
class DynamoEventRepositoryIT {
    private static final Event EXPECTED_EVENT = new Event(
            new EventId("event-123"),
            "The Example Tour",
            new VenueId("venue-456"),
            Instant.parse("2026-10-10T03:00:00Z"),
            "CONCERT",
            EventStatus.SCHEDULED,
            "A canonical event stored in DynamoDB");

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoEventRepository repository;
    private String tableName;
    private Optional<Event> actual;

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
    void readsCanonicalEventById() {
        given(EXPECTED_EVENT);
        whenFindById(EXPECTED_EVENT.id());
        thenExpect(EXPECTED_EVENT);
    }

    @Test
    void missingEventReturnsEmpty() {
        given();
        whenFindById(new EventId("missing"));
        thenExpectMissing();
    }

    @Test
    void rejectsEventRowWhosePayloadIdDoesNotMatchKey() {
        given();
        Map<String, AttributeValue> row = toItem(EXPECTED_EVENT);
        row.put("eventId", string("event-other"));
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(row)
                .build());

        assertThatThrownBy(() -> repository.findById(EXPECTED_EVENT.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("event metadata identity mismatch for event-123");
    }

    private void given(Event... events) {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-events-" + UUID.randomUUID();
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
        repository = new DynamoEventRepository(dynamoDb, tableName);
        for (Event event : events) {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(toItem(event))
                    .build());
        }
        actual = null;
    }

    private void whenFindById(EventId eventId) {
        actual = repository.findById(eventId);
    }

    private void thenExpect(Event event) {
        assertThat(actual).contains(event);
    }

    private void thenExpectMissing() {
        assertThat(actual).isEmpty();
    }

    private static Map<String, AttributeValue> toItem(Event event) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", string(DynamoEventRepository.eventPk(event.id())));
        item.put("entityType", string("EVENT"));
        item.put("eventId", string(event.id().value()));
        item.put("name", string(event.name()));
        item.put("venueId", string(event.venueId().value()));
        item.put("startsAt", number(event.startsAt().toEpochMilli()));
        item.put("category", string(event.category()));
        item.put("status", string(event.status().name()));
        item.put("description", string(event.description()));
        return item;
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
