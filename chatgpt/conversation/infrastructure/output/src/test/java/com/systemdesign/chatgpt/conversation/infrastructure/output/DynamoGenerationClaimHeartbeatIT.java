package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
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

@Testcontainers
class DynamoGenerationClaimHeartbeatIT {
    @Container static final FlociContainer FLOCI = new FlociContainer();
    private static final Instant NOW = Instant.parse("2026-09-10T22:00:00Z");

    private DynamoDbClient dynamoDb;
    private DynamoConversationTurnStore store;
    private DynamoGenerationLeaseTurnRepository leases;
    private String tableName;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-generation-heartbeat-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .build());
        store = new DynamoConversationTurnStore(dynamoDb, tableName);
        leases = new DynamoGenerationLeaseTurnRepository(store, dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void renewsOnlyTheActiveClaimAndMovesLeaseForward() {
        UUID conversationId = UUID.randomUUID();
        Conversation base = Conversation.start(conversationId, "user-1", NOW);
        store.save(base);
        Conversation turn = Conversation.start(conversationId, "user-1", NOW);
        Message user = new Message(UUID.randomUUID(), MessageRole.USER, "hello", NOW.plusSeconds(1));
        turn.append(user);
        Generation pending = Generation.pending(
                UUID.randomUUID(), conversationId, "request-1", "hello", user.id(), user.createdAt());
        store.begin(turn, pending);

        Generation claimed = leases.claim(pending.id(), NOW.plusSeconds(2), NOW.plusSeconds(12)).orElseThrow();
        Instant renewedAt = NOW.plusSeconds(8);
        Instant renewedUntil = NOW.plusSeconds(18);

        assertThat(leases.renewClaim(claimed.id(), claimed.claimToken(), renewedAt, renewedUntil)).isTrue();
        Generation renewed = leases.findGenerationById(claimed.id()).orElseThrow();
        assertThat(renewed.claimToken()).isEqualTo(claimed.claimToken());
        assertThat(renewed.updatedAt()).isEqualTo(renewedAt);
        assertThat(renewed.leaseUntil()).isEqualTo(renewedUntil);

        assertThat(leases.renewClaim(claimed.id(), UUID.randomUUID(), NOW.plusSeconds(9), NOW.plusSeconds(19))).isFalse();
        assertThat(leases.claim(claimed.id(), NOW.plusSeconds(13), NOW.plusSeconds(23))).isEmpty();
        assertThat(leases.claim(claimed.id(), NOW.plusSeconds(18), NOW.plusSeconds(28))).isPresent();
    }
}
