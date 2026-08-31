package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import java.time.Instant;
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
class DynamoAdmissionRegulationLeaseGatewayIT {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final Instant NOW = Instant.parse("2026-08-28T18:00:00Z");

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private String tableName;
    private DynamoAdmissionRegulationLeaseGateway leaseGateway;
    private boolean firstAcquire;
    private boolean ownerRenewal;
    private boolean contenderBeforeExpiry;
    private boolean contenderAfterExpiry;

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void oneRegulatorOwnsEventUntilLeaseExpires() {
        givenEnabledAdmission();
        whenTwoRegulatorsContendAcrossLeaseExpiry();
        thenExpectSingleOwnerUntilExpiry();
    }

    @Test
    void leaseAcquisitionRejectsMismatchedAdmissionIdentityWithoutMutatingRow() {
        initialize();
        putRawAdmission("event-other");
        leaseGateway = new DynamoAdmissionRegulationLeaseGateway(dynamoDb, tableName);

        assertThatThrownBy(() -> leaseGateway.tryAcquireOrRenew(
                        EVENT_ID, "regulator-a", NOW, NOW.plusSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("admission record identity mismatch for event event-123");
        assertThat(admissionItem()).doesNotContainKeys("regulatorId", "regulatorLeaseExpiresAt");
    }

    private void givenEnabledAdmission() {
        initialize();
        new DynamoWaitingRoomRepository(dynamoDb, tableName)
                .initializeAdmission(new EventAdmission(EVENT_ID, NOW));
        leaseGateway = new DynamoAdmissionRegulationLeaseGateway(dynamoDb, tableName);
        firstAcquire = false;
        ownerRenewal = false;
        contenderBeforeExpiry = false;
        contenderAfterExpiry = false;
    }

    private void whenTwoRegulatorsContendAcrossLeaseExpiry() {
        firstAcquire = leaseGateway.tryAcquireOrRenew(
                EVENT_ID, "regulator-a", NOW, NOW.plusSeconds(5));
        ownerRenewal = leaseGateway.tryAcquireOrRenew(
                EVENT_ID, "regulator-a", NOW.plusSeconds(1), NOW.plusSeconds(6));
        contenderBeforeExpiry = leaseGateway.tryAcquireOrRenew(
                EVENT_ID, "regulator-b", NOW.plusSeconds(2), NOW.plusSeconds(7));
        contenderAfterExpiry = leaseGateway.tryAcquireOrRenew(
                EVENT_ID, "regulator-b", NOW.plusSeconds(7), NOW.plusSeconds(12));
    }

    private void thenExpectSingleOwnerUntilExpiry() {
        assertThat(firstAcquire).isTrue();
        assertThat(ownerRenewal).isTrue();
        assertThat(contenderBeforeExpiry).isFalse();
        assertThat(contenderAfterExpiry).isTrue();
        assertThat(admissionItem().get("regulatorId").s()).isEqualTo("regulator-b");
        assertThat(Long.parseLong(admissionItem().get("regulatorLeaseExpiresAt").n()))
                .isEqualTo(NOW.plusSeconds(12).toEpochMilli());
    }

    private void putRawAdmission(String storedEventId) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", string(DynamoWaitingRoomRepository.admissionPk(EVENT_ID)),
                        "entityType", string("EVENT_ADMISSION"),
                        "eventId", string(storedEventId),
                        "admittedThrough", number(NOW.toEpochMilli())))
                .build());
    }

    private void initialize() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-admission-lease-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder()
                        .attributeName("pk").keyType(KeyType.HASH).build())
                .build());
    }

    private Map<String, AttributeValue> admissionItem() {
        return dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", string(DynamoWaitingRoomRepository.admissionPk(EVENT_ID))))
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
