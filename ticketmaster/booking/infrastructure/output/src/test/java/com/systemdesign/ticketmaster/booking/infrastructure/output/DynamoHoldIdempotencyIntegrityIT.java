package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import io.floci.testcontainers.FlociContainer;
import java.net.URI;
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
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class DynamoHoldIdempotencyIntegrityIT {
    private static final EventId EVENT_ID = new EventId("event-1");
    private static final UserId USER_ID = new UserId("user-1");

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private String tableName;
    private HoldIdempotencyKey key;
    private DynamoHoldRepository repository;
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
    void mappingToMissingHoldFailsClosedInsteadOfLookingLikeANewRequest() {
        givenIdempotencyMappingToMissingHold();
        whenMappingIsResolved();
        thenExpectIntegrityFailure();
    }

    private void givenIdempotencyMappingToMissingHold() {
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
        key = new HoldIdempotencyKey("retry-key");
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", string(DynamoKeys.holdIdempotencyPk(EVENT_ID, USER_ID, key)),
                        "entityType", string("HOLD_IDEMPOTENCY"),
                        "eventId", string(EVENT_ID.value()),
                        "userId", string(USER_ID.value()),
                        "idempotencyKey", string(key.value()),
                        "holdId", string("missing-hold")))
                .build());
        repository = new DynamoHoldRepository(dynamoDb, tableName);
        thrown = null;
    }

    private void whenMappingIsResolved() {
        try {
            repository.findByIdempotencyKey(EVENT_ID, USER_ID, key);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectIntegrityFailure() {
        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("references missing hold");
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
