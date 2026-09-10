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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DynamoGenerationContinuationStoreIT {
    @Container static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private String tableName;
    private DynamoConversationTurnStore turnStore;
    private DynamoRunningMessageStore continuationStore;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-continuation-" + UUID.randomUUID();
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
        continuationStore = new DynamoRunningMessageStore(dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void atomicallyPersistsConversationTranscriptAndGenerationContinuationForRetry() {
        Instant now = Instant.parse("2026-09-10T20:00:00Z");
        UUID conversationId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        Message user = new Message(UUID.randomUUID(), MessageRole.USER, "hello", now.plusSeconds(1));
        Conversation conversation = Conversation.start(conversationId, "user-1", now);
        turnStore.save(conversation);
        conversation.append(user);
        Generation generation = Generation.pending(
                generationId, conversationId, "request-1", "hello", user.id(), user.createdAt());
        turnStore.begin(conversation, generation);
        turnStore.claim(generationId, now.plusSeconds(2)).orElseThrow();

        Message request = new Message(
                UUID.randomUUID(), MessageRole.ASSISTANT, "tool-request", now.plusSeconds(3), generationId);
        Message result = new Message(
                UUID.randomUUID(), MessageRole.TOOL, "tool-result", now.plusSeconds(4), generationId);

        assertThat(continuationStore.append(generationId, List.of(request, result))).isTrue();

        DynamoRunningMessageStore reloaded = new DynamoRunningMessageStore(dynamoDb, tableName);
        assertThat(reloaded.list(generationId))
                .extracting(Message::role, Message::content, Message::generationId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MessageRole.ASSISTANT, "tool-request", generationId),
                        org.assertj.core.groups.Tuple.tuple(MessageRole.TOOL, "tool-result", generationId));
        assertThat(turnStore.findById(conversationId).orElseThrow().messages())
                .extracting(Message::content)
                .containsExactly("hello", "tool-request", "tool-result");
    }

    @Test
    void cancellationRejectsBothTranscriptAndContinuationWrites() {
        Instant now = Instant.parse("2026-09-10T20:00:00Z");
        UUID conversationId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        Message user = new Message(UUID.randomUUID(), MessageRole.USER, "hello", now.plusSeconds(1));
        Conversation conversation = Conversation.start(conversationId, "user-1", now);
        turnStore.save(conversation);
        conversation.append(user);
        Generation generation = Generation.pending(
                generationId, conversationId, "request-1", "hello", user.id(), user.createdAt());
        turnStore.begin(conversation, generation);
        turnStore.claim(generationId, now.plusSeconds(2)).orElseThrow();
        turnStore.cancel(generationId, now.plusSeconds(3)).orElseThrow();

        Message result = new Message(
                UUID.randomUUID(), MessageRole.TOOL, "too-late", now.plusSeconds(4), generationId);

        assertThat(continuationStore.append(generationId, List.of(result))).isFalse();
        assertThat(continuationStore.list(generationId)).isEmpty();
        assertThat(turnStore.findById(conversationId).orElseThrow().messages())
                .extracting(Message::content)
                .containsExactly("hello");
    }
}
