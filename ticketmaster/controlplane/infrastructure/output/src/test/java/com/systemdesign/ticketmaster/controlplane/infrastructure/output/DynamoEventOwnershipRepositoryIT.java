package com.systemdesign.ticketmaster.controlplane.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.controlplane.domain.EventId;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnership;
import com.systemdesign.ticketmaster.controlplane.domain.OwnershipConflictException;
import com.systemdesign.ticketmaster.controlplane.domain.RegionId;
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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class DynamoEventOwnershipRepositoryIT {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final RegionId WEST = new RegionId("us-west-2");
    private static final RegionId EAST = new RegionId("us-east-1");

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoEventOwnershipRepository repository;
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
    void assignsAndTransfersOwnershipWithMonotonicEpoch() {
        givenRepository();

        EventOwnership assigned = repository.assignIfAbsent(new EventOwnership(EVENT_ID, WEST, 1));
        EventOwnership transferred = repository.transfer(EVENT_ID, WEST, 1, EAST);

        assertThat(assigned).isEqualTo(new EventOwnership(EVENT_ID, WEST, 1));
        assertThat(transferred).isEqualTo(new EventOwnership(EVENT_ID, EAST, 2));
        assertThat(repository.findByEventId(EVENT_ID)).contains(transferred);
    }

    @Test
    void rejectsDuplicateInitialAssignment() {
        givenRepository();
        repository.assignIfAbsent(new EventOwnership(EVENT_ID, WEST, 1));

        assertThatThrownBy(() -> repository.assignIfAbsent(new EventOwnership(EVENT_ID, EAST, 1)))
                .isInstanceOf(OwnershipConflictException.class);
    }

    @Test
    void rejectsTransferUsingStaleOwnerAndEpoch() {
        givenRepository();
        repository.assignIfAbsent(new EventOwnership(EVENT_ID, WEST, 1));
        repository.transfer(EVENT_ID, WEST, 1, EAST);

        assertThatThrownBy(() -> repository.transfer(EVENT_ID, WEST, 1, EAST))
                .isInstanceOf(OwnershipConflictException.class);
        assertThat(repository.findByEventId(EVENT_ID))
                .contains(new EventOwnership(EVENT_ID, EAST, 2));
    }

    @Test
    void rejectsOwnershipRowWhosePayloadEventDoesNotMatchKey() {
        givenRepository();
        putOwnershipRow("event-other", WEST, 1);

        assertThatThrownBy(() -> repository.findByEventId(EVENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("event ownership identity mismatch for event-123");
    }

    @Test
    void rejectsTransferWithoutMutatingOwnershipRowWhosePayloadEventDoesNotMatchKey() {
        givenRepository();
        putOwnershipRow("event-other", WEST, 1);

        assertThatThrownBy(() -> repository.transfer(EVENT_ID, WEST, 1, EAST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("event ownership identity mismatch for event-123");

        Map<String, AttributeValue> row = rawOwnershipRow();
        assertThat(row.get("eventId").s()).isEqualTo("event-other");
        assertThat(row.get("ownerRegion").s()).isEqualTo(WEST.value());
        assertThat(row.get("epoch").n()).isEqualTo("1");
    }

    private void givenRepository() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-controlplane-" + UUID.randomUUID();
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
        repository = new DynamoEventOwnershipRepository(dynamoDb, tableName);
    }

    private void putOwnershipRow(String payloadEventId, RegionId owner, long epoch) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", string(DynamoEventOwnershipRepository.pk(EVENT_ID)),
                        "eventId", string(payloadEventId),
                        "ownerRegion", string(owner.value()),
                        "epoch", number(epoch)))
                .build());
    }

    private Map<String, AttributeValue> rawOwnershipRow() {
        return dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", string(DynamoEventOwnershipRepository.pk(EVENT_ID))))
                        .consistentRead(true)
                        .build())
                .item();
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
