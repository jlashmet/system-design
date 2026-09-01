package com.systemdesign.ticketmaster.events.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.events.domain.Venue;
import com.systemdesign.ticketmaster.events.domain.VenueId;
import io.floci.testcontainers.FlociContainer;
import java.net.URI;
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
class DynamoVenueRepositoryIT {
    private static final Venue EXPECTED_VENUE = new Venue(
            new VenueId("venue-456"),
            "Hollywood Bowl",
            "Los Angeles");

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoVenueRepository repository;
    private String tableName;
    private Optional<Venue> actual;

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
    void readsCanonicalVenueById() {
        given(EXPECTED_VENUE);
        whenFindById(EXPECTED_VENUE.id());
        thenExpect(EXPECTED_VENUE);
    }

    @Test
    void missingVenueReturnsEmpty() {
        given();
        whenFindById(new VenueId("missing"));
        thenExpectMissing();
    }

    @Test
    void rejectsVenueRowWhosePayloadIdDoesNotMatchKey() {
        given();
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", string(DynamoVenueRepository.venuePk(EXPECTED_VENUE.id())),
                        "entityType", string("VENUE"),
                        "venueId", string("venue-other"),
                        "name", string(EXPECTED_VENUE.name()),
                        "city", string(EXPECTED_VENUE.city())))
                .build());

        assertThatThrownBy(() -> repository.findById(EXPECTED_VENUE.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("venue metadata identity mismatch for venue-456");
    }

    private void given(Venue... venues) {
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
        repository = new DynamoVenueRepository(dynamoDb, tableName);
        for (Venue venue : venues) {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(toItem(venue))
                    .build());
        }
        actual = null;
    }

    private void whenFindById(VenueId venueId) {
        actual = repository.findById(venueId);
    }

    private void thenExpect(Venue venue) {
        assertThat(actual).contains(venue);
    }

    private void thenExpectMissing() {
        assertThat(actual).isEmpty();
    }

    private static Map<String, AttributeValue> toItem(Venue venue) {
        return Map.of(
                "pk", string(DynamoVenueRepository.venuePk(venue.id())),
                "entityType", string("VENUE"),
                "venueId", string(venue.id().value()),
                "name", string(venue.name()),
                "city", string(venue.city()));
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
