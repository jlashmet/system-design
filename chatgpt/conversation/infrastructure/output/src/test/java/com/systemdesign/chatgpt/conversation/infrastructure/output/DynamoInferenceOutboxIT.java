package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.InferenceOutbox;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DynamoInferenceOutboxIT {
    private static final Instant NOW = Instant.parse("2026-09-10T23:10:00Z");

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoConversationTurnStore conversationStore;
    private DynamoOutboxTurnRepository store;
    private String tableName;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-outbox-" + UUID.randomUUID();
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
        conversationStore = new DynamoConversationTurnStore(dynamoDb, tableName);
        var leased = new DynamoGenerationLeaseTurnRepository(conversationStore, dynamoDb, tableName);
        store = new DynamoOutboxTurnRepository(leased, dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void beginAtomicallyCreatesOneLogicalOutboxEntryAndReplayDoesNotDuplicateIt() {
        UUID conversationId = UUID.randomUUID();
        conversationStore.save(Conversation.start(conversationId, "user-1", NOW));
        Conversation first = conversationWithUserMessage(conversationId, "hello");
        Generation generation = pending(conversationId, first.messages().getFirst(), "request-1", "hello");

        TurnRepository.BeginResult created = store.begin(first, generation);
        Conversation duplicate = conversationWithUserMessage(conversationId, "hello");
        Generation duplicateGeneration = pending(conversationId, duplicate.messages().getFirst(), "request-1", "hello");
        TurnRepository.BeginResult replay = store.begin(duplicate, duplicateGeneration);

        assertThat(created.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.generation().id()).isEqualTo(generation.id());
        InferenceOutbox.Entry entry = store.claimNext(NOW.plusSeconds(2), NOW.plusSeconds(32)).orElseThrow();
        assertThat(entry.generationId()).isEqualTo(generation.id());
        assertThat(store.claimNext(NOW.plusSeconds(3), NOW.plusSeconds(33))).isEmpty();
        assertThat(store.markDispatched(generation.id(), entry.claimToken(), NOW.plusSeconds(4))).isTrue();
        assertThat(store.claimNext(NOW.plusSeconds(40), NOW.plusSeconds(70))).isEmpty();
    }

    @Test
    void expiredClaimCanBeReclaimedAndStaleDispatcherIsFenced() {
        UUID conversationId = UUID.randomUUID();
        conversationStore.save(Conversation.start(conversationId, "user-1", NOW));
        Conversation conversation = conversationWithUserMessage(conversationId, "hello");
        Generation generation = pending(conversationId, conversation.messages().getFirst(), "request-1", "hello");
        store.begin(conversation, generation);

        InferenceOutbox.Entry first = store.claimNext(NOW.plusSeconds(2), NOW.plusSeconds(5)).orElseThrow();
        InferenceOutbox.Entry second = store.claimNext(NOW.plusSeconds(6), NOW.plusSeconds(36)).orElseThrow();

        assertThat(second.generationId()).isEqualTo(generation.id());
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(store.markDispatched(generation.id(), first.claimToken(), NOW.plusSeconds(7))).isFalse();
        assertThat(store.markDispatched(generation.id(), second.claimToken(), NOW.plusSeconds(8))).isTrue();
    }

    private Conversation conversationWithUserMessage(UUID conversationId, String content) {
        Conversation conversation = Conversation.start(conversationId, "user-1", NOW);
        conversation.append(new Message(UUID.randomUUID(), MessageRole.USER, content, NOW.plusSeconds(1)));
        return conversation;
    }

    private Generation pending(UUID conversationId, Message userMessage, String key, String content) {
        return Generation.pending(UUID.randomUUID(), conversationId, key, content, userMessage.id(), userMessage.createdAt());
    }
}
