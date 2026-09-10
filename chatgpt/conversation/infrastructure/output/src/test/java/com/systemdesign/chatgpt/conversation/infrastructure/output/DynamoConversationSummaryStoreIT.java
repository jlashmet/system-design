package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryStore;
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
class DynamoConversationSummaryStoreIT {
    private static final Instant NOW = Instant.parse("2026-09-10T18:20:00Z");

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoConversationSummaryStore store;
    private String tableName;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-summary-" + UUID.randomUUID();
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
        store = new DynamoConversationSummaryStore(dynamoDb, tableName);
    }

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
    void roundTripsConversationSummaryThroughFlociDynamoDb() {
        ConversationSummaryStore.Summary summary = summary("latest", NOW);

        store.save(summary);

        assertThat(store.find(summary.conversationId())).contains(summary);
    }

    @Test
    void delayedOlderRefreshDoesNotReplaceNewerSummary() {
        UUID conversationId = UUID.randomUUID();
        ConversationSummaryStore.Summary newer = summary(conversationId, "newer", NOW.plusSeconds(10));
        ConversationSummaryStore.Summary older = summary(conversationId, "older", NOW);

        store.save(newer);
        store.save(older);

        assertThat(store.find(conversationId)).contains(newer);
    }

    private ConversationSummaryStore.Summary summary(String content, Instant updatedAt) {
        return summary(UUID.randomUUID(), content, updatedAt);
    }

    private ConversationSummaryStore.Summary summary(UUID conversationId, String content, Instant updatedAt) {
        return new ConversationSummaryStore.Summary(
                UUID.randomUUID(),
                conversationId,
                UUID.randomUUID(),
                content,
                updatedAt);
    }
}
