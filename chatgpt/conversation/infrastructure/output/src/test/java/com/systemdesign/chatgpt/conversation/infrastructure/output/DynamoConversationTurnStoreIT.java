package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
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
class DynamoConversationTurnStoreIT {
    private static final Instant NOW = Instant.parse("2026-09-10T18:40:00Z");

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoConversationTurnStore store;
    private String tableName;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-conversation-" + UUID.randomUUID();
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
        store = new DynamoConversationTurnStore(dynamoDb, tableName);
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
    void atomicallyBeginsAndReplaysOneLogicalTurn() {
        UUID conversationId = UUID.randomUUID();
        Conversation firstConversation = conversationWithUserMessage(conversationId, "hello");
        Message firstUser = firstConversation.messages().getFirst();
        Generation firstGeneration = pending(conversationId, firstUser, "request-1", "hello");
        store.save(Conversation.start(conversationId, "user-1", NOW));

        TurnRepository.BeginResult first = store.begin(firstConversation, firstGeneration);
        Conversation duplicateConversation = conversationWithUserMessage(conversationId, "hello");
        Generation duplicateGeneration = pending(
                conversationId, duplicateConversation.messages().getFirst(), "request-1", "hello");
        TurnRepository.BeginResult replay = store.begin(duplicateConversation, duplicateGeneration);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.generation().id()).isEqualTo(firstGeneration.id());
        assertThat(store.findByIdempotencyKey(conversationId, "request-1")).contains(firstGeneration);
        assertThat(store.findById(conversationId).orElseThrow().messages())
                .extracting(Message::id, Message::content)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(firstUser.id(), "hello"));
    }

    @Test
    void claimsFailsAndAllowsRetryClaim() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversationWithUserMessage(conversationId, "hello");
        Generation generation = pending(conversationId, conversation.messages().getFirst(), "request-1", "hello");
        store.save(Conversation.start(conversationId, "user-1", NOW));
        store.begin(conversation, generation);

        Generation running = store.claim(generation.id(), NOW.plusSeconds(2)).orElseThrow();
        assertThat(store.claim(generation.id(), NOW.plusSeconds(3))).isEmpty();
        store.fail(running.failed(NOW.plusSeconds(4)));
        Generation retried = store.claim(generation.id(), NOW.plusSeconds(5)).orElseThrow();

        assertThat(running.status()).isEqualTo(GenerationStatus.RUNNING);
        assertThat(retried.status()).isEqualTo(GenerationStatus.RUNNING);
        assertThat(retried.updatedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void appendsRunningToolTranscriptAtomically() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversationWithUserMessage(conversationId, "hello");
        Generation generation = pending(conversationId, conversation.messages().getFirst(), "request-1", "hello");
        store.save(Conversation.start(conversationId, "user-1", NOW));
        store.begin(conversation, generation);
        store.claim(generation.id(), NOW.plusSeconds(2)).orElseThrow();
        Message toolRequest = new Message(UUID.randomUUID(), MessageRole.ASSISTANT, "Tool requests", NOW.plusSeconds(3));
        Message toolResult = new Message(UUID.randomUUID(), MessageRole.TOOL, "result=value", NOW.plusSeconds(4));

        assertThat(store.append(generation.id(), List.of(toolRequest, toolResult))).isTrue();

        assertThat(store.findById(conversationId).orElseThrow().messages())
                .extracting(Message::role, Message::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MessageRole.USER, "hello"),
                        org.assertj.core.groups.Tuple.tuple(MessageRole.ASSISTANT, "Tool requests"),
                        org.assertj.core.groups.Tuple.tuple(MessageRole.TOOL, "result=value"));
    }

    @Test
    void cancellationPreventsToolTranscriptPersistence() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversationWithUserMessage(conversationId, "hello");
        Generation generation = pending(conversationId, conversation.messages().getFirst(), "request-1", "hello");
        store.save(Conversation.start(conversationId, "user-1", NOW));
        store.begin(conversation, generation);
        store.claim(generation.id(), NOW.plusSeconds(2)).orElseThrow();
        store.cancel(generation.id(), NOW.plusSeconds(3)).orElseThrow();
        Message toolResult = new Message(UUID.randomUUID(), MessageRole.TOOL, "too late", NOW.plusSeconds(4));

        assertThat(store.append(generation.id(), List.of(toolResult))).isFalse();
        assertThat(store.findById(conversationId).orElseThrow().messages())
                .extracting(Message::content)
                .containsExactly("hello");
    }

    @Test
    void completesGenerationAndAssistantMessageAtomically() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversationWithUserMessage(conversationId, "hello");
        Generation generation = pending(conversationId, conversation.messages().getFirst(), "request-1", "hello");
        store.save(Conversation.start(conversationId, "user-1", NOW));
        store.begin(conversation, generation);
        Generation running = store.claim(generation.id(), NOW.plusSeconds(2)).orElseThrow();
        Message assistant = new Message(
                UUID.randomUUID(), MessageRole.ASSISTANT, "assistant: hello", NOW.plusSeconds(3));
        conversation.append(assistant);

        store.complete(conversation, running.completed(assistant.id(), NOW.plusSeconds(3)));

        Generation completed = store.findGenerationById(generation.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(completed.assistantMessageId()).isEqualTo(assistant.id());
        assertThat(store.findById(conversationId).orElseThrow().messages())
                .extracting(Message::role, Message::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MessageRole.USER, "hello"),
                        org.assertj.core.groups.Tuple.tuple(MessageRole.ASSISTANT, "assistant: hello"));
    }

    @Test
    void cancellationPreventsLateCompletionFromPersistingAssistant() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversationWithUserMessage(conversationId, "hello");
        Generation generation = pending(conversationId, conversation.messages().getFirst(), "request-1", "hello");
        store.save(Conversation.start(conversationId, "user-1", NOW));
        store.begin(conversation, generation);
        Generation running = store.claim(generation.id(), NOW.plusSeconds(2)).orElseThrow();
        Generation cancelled = store.cancel(generation.id(), NOW.plusSeconds(3)).orElseThrow();
        Message assistant = new Message(
                UUID.randomUUID(), MessageRole.ASSISTANT, "too late", NOW.plusSeconds(4));
        conversation.append(assistant);

        store.complete(conversation, running.completed(assistant.id(), NOW.plusSeconds(4)));

        assertThat(cancelled.status()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(store.findGenerationById(generation.id()).orElseThrow().status())
                .isEqualTo(GenerationStatus.CANCELLED);
        assertThat(store.findById(conversationId).orElseThrow().messages())
                .extracting(Message::content)
                .containsExactly("hello");
    }

    private Conversation conversationWithUserMessage(UUID conversationId, String content) {
        Conversation conversation = Conversation.start(conversationId, "user-1", NOW);
        conversation.append(new Message(UUID.randomUUID(), MessageRole.USER, content, NOW.plusSeconds(1)));
        return conversation;
    }

    private Generation pending(UUID conversationId, Message userMessage, String key, String content) {
        return Generation.pending(
                UUID.randomUUID(), conversationId, key, content, userMessage.id(), userMessage.createdAt());
    }
}
