package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
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
class DynamoGenerationLeaseIT {
    @Container static final FlociContainer FLOCI = new FlociContainer();
    private static final Instant NOW = Instant.parse("2026-09-10T21:30:00Z");

    private DynamoDbClient dynamoDb;
    private DynamoConversationTurnStore store;
    private DynamoRunningMessageStore runningMessages;
    private String tableName;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-generation-lease-" + UUID.randomUUID();
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
        runningMessages = new DynamoRunningMessageStore(dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void activeLeaseBlocksTakeoverThenExpiredLeaseIsReclaimedWithNewFence() {
        Fixture fixture = begin();
        Generation first = store.claim(fixture.generation.id(), NOW.plusSeconds(2), NOW.plusSeconds(12)).orElseThrow();

        assertThat(store.claim(fixture.generation.id(), NOW.plusSeconds(3), NOW.plusSeconds(13))).isEmpty();

        Generation second = store.claim(fixture.generation.id(), NOW.plusSeconds(12), NOW.plusSeconds(22)).orElseThrow();
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(second.leaseUntil()).isEqualTo(NOW.plusSeconds(22));
        assertThat(second.status()).isEqualTo(GenerationStatus.RUNNING);
    }

    @Test
    void staleWorkerCannotAppendFailOrCompleteAfterLeaseTakeover() {
        Fixture fixture = begin();
        Generation first = store.claim(fixture.generation.id(), NOW.plusSeconds(2), NOW.plusSeconds(12)).orElseThrow();
        Generation second = store.claim(fixture.generation.id(), NOW.plusSeconds(12), NOW.plusSeconds(22)).orElseThrow();

        Message staleTool = new Message(UUID.randomUUID(), MessageRole.TOOL, "stale-tool", NOW.plusSeconds(13), first.id());
        assertThat(runningMessages.append(first.id(), first.claimToken(), List.of(staleTool))).isFalse();

        store.fail(first.failed(NOW.plusSeconds(14)));
        assertThat(store.findGenerationById(first.id()).orElseThrow().claimToken()).isEqualTo(second.claimToken());
        assertThat(store.findGenerationById(first.id()).orElseThrow().status()).isEqualTo(GenerationStatus.RUNNING);

        Message staleAssistant = new Message(UUID.randomUUID(), MessageRole.ASSISTANT, "stale-answer", NOW.plusSeconds(15), first.id());
        Conversation staleConversation = store.findById(fixture.conversation.id()).orElseThrow();
        staleConversation.append(staleAssistant);
        store.complete(staleConversation, first.completed(staleAssistant.id(), NOW.plusSeconds(15)));

        Generation persisted = store.findGenerationById(first.id()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(GenerationStatus.RUNNING);
        assertThat(persisted.claimToken()).isEqualTo(second.claimToken());
        assertThat(store.findById(fixture.conversation.id()).orElseThrow().messages())
                .extracting(Message::content)
                .containsExactly("hello");
    }

    private Fixture begin() {
        UUID conversationId = UUID.randomUUID();
        Conversation base = Conversation.start(conversationId, "user-1", NOW);
        store.save(base);
        Conversation turn = Conversation.start(conversationId, "user-1", NOW);
        Message user = new Message(UUID.randomUUID(), MessageRole.USER, "hello", NOW.plusSeconds(1));
        turn.append(user);
        Generation generation = Generation.pending(UUID.randomUUID(), conversationId, "request-1", "hello", user.id(), user.createdAt());
        store.begin(turn, generation);
        return new Fixture(turn, generation);
    }

    private record Fixture(Conversation conversation, Generation generation) { }
}
