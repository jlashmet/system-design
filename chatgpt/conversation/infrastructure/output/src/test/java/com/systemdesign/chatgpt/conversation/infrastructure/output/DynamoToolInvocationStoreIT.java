package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ToolInvocationStore;
import com.systemdesign.chatgpt.conversation.domain.ToolResult;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class DynamoToolInvocationStoreIT {
    @Container static final FlociContainer FLOCI = new FlociContainer();
    private DynamoDbClient dynamoDb;
    private DynamoToolInvocationStore store;
    private String tableName;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-tool-invocation-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .build());
        store = new DynamoToolInvocationStore(dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void blocksConcurrentClaimThenAllowsLeaseExpiryAndFencesStaleCompletion() {
        UUID generationId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-10T20:00:00Z");

        ToolInvocationStore.Claim first = store.claim(generationId, callId, "charge", "fp", now, now.plusSeconds(10));
        assertThat(first.status()).isEqualTo(ToolInvocationStore.ClaimStatus.CLAIMED);
        assertThat(store.claim(generationId, callId, "charge", "fp", now.plusSeconds(1), now.plusSeconds(11)).status())
                .isEqualTo(ToolInvocationStore.ClaimStatus.BUSY);

        ToolInvocationStore.Claim replacement = store.claim(
                generationId, callId, "charge", "fp", now.plusSeconds(11), now.plusSeconds(21));
        assertThat(replacement.status()).isEqualTo(ToolInvocationStore.ClaimStatus.CLAIMED);
        ToolResult result = new ToolResult(callId, ToolResult.Status.SUCCESS, "charged");
        assertThatThrownBy(() -> store.complete(generationId, callId, first.claimToken(), result, now.plusSeconds(12)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");

        store.complete(generationId, callId, replacement.claimToken(), result, now.plusSeconds(12));
        ToolInvocationStore.Claim replay = store.claim(
                generationId, callId, "charge", "fp", now.plusSeconds(13), now.plusSeconds(23));
        assertThat(replay.status()).isEqualTo(ToolInvocationStore.ClaimStatus.COMPLETED);
        assertThat(replay.completedResult()).isEqualTo(result);
    }

    @Test
    void rejectsReuseOfToolCallIdWithDifferentRequestFingerprint() {
        UUID generationId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-10T20:00:00Z");
        store.claim(generationId, callId, "charge", "fp-1", now, now.plusSeconds(10));

        assertThatThrownBy(() -> store.claim(generationId, callId, "charge", "fp-2", now.plusSeconds(1), now.plusSeconds(11)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different request");
    }
}
