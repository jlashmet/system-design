package com.systemdesign.chatgpt.conversation.infrastructure.output;

import io.floci.testcontainers.FlociContainer;
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
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DynamoFixedWindowInferenceQuotaIT {
    @Container static final FlociContainer FLOCI = new FlociContainer();
    private DynamoDbClient dynamoDb;
    private DynamoFixedWindowInferenceQuota quota;
    private String tableName;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-quota-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .build());
        quota = new DynamoFixedWindowInferenceQuota(dynamoDb, tableName, 2, Duration.ofMinutes(1));
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void admitsIdempotentReplayWithoutDoubleChargingAndResetsNextWindow() {
        Instant now = Instant.parse("2026-09-10T20:03:10Z");

        assertThat(quota.tryAcquire("user-1", "request-1", now)).isTrue();
        assertThat(quota.tryAcquire("user-1", "request-1", now.plusSeconds(1))).isTrue();
        assertThat(quota.tryAcquire("user-1", "request-2", now.plusSeconds(2))).isTrue();
        assertThat(quota.tryAcquire("user-1", "request-3", now.plusSeconds(3))).isFalse();
        assertThat(quota.tryAcquire("user-2", "request-1", now.plusSeconds(3))).isTrue();
        assertThat(quota.tryAcquire("user-1", "request-3", now.plusSeconds(60))).isTrue();
    }
}
