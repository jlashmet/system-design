package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationMessagePageStore;
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
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class DynamoConversationMessagePageStoreIT {
    @Container static final FlociContainer FLOCI = new FlociContainer();
    private DynamoDbClient dynamoDb;
    private String tableName;
    private DynamoConversationTurnStore writer;
    private DynamoConversationMessagePageStore reader;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-message-page-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .build());
        writer = new DynamoConversationTurnStore(dynamoDb, tableName);
        reader = new DynamoConversationMessagePageStore(dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void pagesMessagesInConversationOrderUsingOpaqueCursor() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = Conversation.start(conversationId, "user-1", Instant.parse("2026-09-10T20:00:00Z"));
        conversation.append(message(MessageRole.USER, "one", 1));
        conversation.append(message(MessageRole.ASSISTANT, "two", 2));
        conversation.append(message(MessageRole.USER, "three", 3));
        writer.save(conversation);

        ConversationMessagePageStore.Page first = reader.read(conversationId, 2, null);
        ConversationMessagePageStore.Page second = reader.read(conversationId, 2, first.nextCursor());

        assertThat(first.messages()).extracting(Message::content).containsExactly("one", "two");
        assertThat(first.nextCursor()).isNotBlank().doesNotContain("MSG#");
        assertThat(second.messages()).extracting(Message::content).containsExactly("three");
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void distinguishesEmptyConversationFromMissingConversationAndRejectsBadCursor() {
        UUID conversationId = UUID.randomUUID();
        writer.save(Conversation.start(conversationId, "user-1", Instant.parse("2026-09-10T20:00:00Z")));

        assertThat(reader.read(conversationId, 10, null).messages()).isEmpty();
        assertThatThrownBy(() -> reader.read(UUID.randomUUID(), 10, null))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> reader.read(conversationId, 10, "not-a-valid-cursor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid message cursor");
    }

    private Message message(MessageRole role, String content, long seconds) {
        return new Message(UUID.randomUUID(), role, content, Instant.parse("2026-09-10T20:00:00Z").plusSeconds(seconds));
    }
}
