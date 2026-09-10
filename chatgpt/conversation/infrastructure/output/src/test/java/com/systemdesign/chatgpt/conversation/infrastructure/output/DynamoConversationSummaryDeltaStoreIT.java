package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryDeltaStore;
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
class DynamoConversationSummaryDeltaStoreIT {
    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private String tableName;
    private DynamoConversationSummaryDeltaStore deltaStore;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-summary-delta-" + UUID.randomUUID();
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
        deltaStore = new DynamoConversationSummaryDeltaStore(dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void readsBoundedRangeAfterSummaryBoundaryAndNeverCrossesCurrentGenerationBoundary() {
        Instant start = Instant.parse("2026-09-10T20:00:00Z");
        UUID conversationId = UUID.randomUUID();
        Message covered = message(MessageRole.ASSISTANT, "covered", start.plusSeconds(1));
        Message missed1 = message(MessageRole.USER, "missed-1", start.plusSeconds(2));
        Message missed2 = message(MessageRole.ASSISTANT, "missed-2", start.plusSeconds(3));
        Message current = message(MessageRole.ASSISTANT, "current", start.plusSeconds(4));
        Message later = message(MessageRole.USER, "later-user", start.plusSeconds(5));
        Conversation conversation = Conversation.start(conversationId, "user-1", start);
        List.of(covered, missed1, missed2, current, later).forEach(conversation::append);
        new DynamoConversationTurnStore(dynamoDb, tableName).save(conversation);

        assertThat(deltaStore.load(conversationId,
                ConversationSummaryDeltaStore.Position.of(covered),
                ConversationSummaryDeltaStore.Position.of(current), 2))
                .extracting(Message::content)
                .containsExactly("missed-1", "missed-2");

        assertThat(deltaStore.load(conversationId,
                ConversationSummaryDeltaStore.Position.of(covered),
                ConversationSummaryDeltaStore.Position.of(current), 10))
                .extracting(Message::content)
                .containsExactly("missed-1", "missed-2", "current")
                .doesNotContain("later-user");
    }

    private Message message(MessageRole role, String content, Instant at) {
        return new Message(UUID.randomUUID(), role, content, at);
    }
}
