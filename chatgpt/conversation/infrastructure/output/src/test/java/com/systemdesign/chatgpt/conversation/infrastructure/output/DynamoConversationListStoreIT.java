package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
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
class DynamoConversationListStoreIT {
    @Container static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private String tableName;
    private DynamoConversationTurnStore canonical;
    private DynamoIndexedConversationRepository repository;
    private DynamoConversationListStore listStore;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-conversation-list-" + UUID.randomUUID();
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
        canonical = new DynamoConversationTurnStore(dynamoDb, tableName);
        repository = new DynamoIndexedConversationRepository(canonical, dynamoDb, tableName);
        listStore = new DynamoConversationListStore(dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void listsOnlyAuthenticatedSubjectsConversationsNewestFirstAcrossPages() {
        Instant base = Instant.parse("2026-09-10T23:40:00Z");
        Conversation older = Conversation.start(UUID.randomUUID(), "user-1", base.plusSeconds(1));
        Conversation otherUser = Conversation.start(UUID.randomUUID(), "user-2", base.plusSeconds(2));
        Conversation newer = Conversation.start(UUID.randomUUID(), "user-1", base.plusSeconds(3));
        repository.save(older);
        repository.save(otherUser);
        repository.save(newer);

        var first = listStore.list("user-1", 1, null);
        var second = listStore.list("user-1", 1, first.nextCursor());

        assertThat(first.conversations()).extracting(metadata -> metadata.conversationId())
                .containsExactly(newer.id());
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.conversations()).extracting(metadata -> metadata.conversationId())
                .containsExactly(older.id());
        assertThat(second.nextCursor()).isNull();
        assertThat(canonical.findById(newer.id())).isPresent();
        assertThat(listStore.list("user-2", 10, null).conversations())
                .extracting(metadata -> metadata.conversationId()).containsExactly(otherUser.id());
    }
}
