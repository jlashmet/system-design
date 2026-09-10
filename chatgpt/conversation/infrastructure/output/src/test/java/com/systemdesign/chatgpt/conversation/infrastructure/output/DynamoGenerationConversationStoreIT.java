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
class DynamoGenerationConversationStoreIT {
    private static final Instant NOW = Instant.parse("2026-09-10T22:00:00Z");

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoConversationTurnStore turnStore;
    private DynamoGenerationConversationStore historyStore;
    private String tableName;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-generation-history-" + UUID.randomUUID();
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
        turnStore = new DynamoConversationTurnStore(dynamoDb, tableName);
        historyStore = new DynamoGenerationConversationStore(dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void returnsOnlyBoundedSuffixThroughGenerationTarget() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = Conversation.start(conversationId, "user-1", NOW);
        Message one = new Message(UUID.randomUUID(), MessageRole.USER, "one", NOW.plusSeconds(1));
        Message two = new Message(UUID.randomUUID(), MessageRole.ASSISTANT, "two", NOW.plusSeconds(2));
        Message target = new Message(UUID.randomUUID(), MessageRole.USER, "target", NOW.plusSeconds(3));
        Message later = new Message(UUID.randomUUID(), MessageRole.USER, "later", NOW.plusSeconds(4));
        conversation.append(one);
        conversation.append(two);
        conversation.append(target);
        conversation.append(later);
        turnStore.save(conversation);
        Generation generation = Generation.pending(
                UUID.randomUUID(), conversationId, "request-1", "target", target.id(), target.createdAt());

        Conversation bounded = historyStore.load(generation, 2).orElseThrow();

        assertThat(bounded.messages()).extracting(Message::content).containsExactly("two", "target");
    }

    @Test
    void missingConversationReturnsEmpty() {
        Message target = new Message(UUID.randomUUID(), MessageRole.USER, "target", NOW.plusSeconds(1));
        Generation generation = Generation.pending(
                UUID.randomUUID(), UUID.randomUUID(), "request-1", "target", target.id(), target.createdAt());

        assertThat(historyStore.load(generation, 10)).isEmpty();
    }
}
