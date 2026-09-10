package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.LongTermMemoryStore;
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
class DynamoLongTermMemoryStoreIT {
    @Container static final FlociContainer FLOCI = new FlociContainer();
    private DynamoDbClient dynamoDb;
    private DynamoLongTermMemoryStore store;
    private String tableName;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-memory-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .build());
        store = new DynamoLongTermMemoryStore(dynamoDb, tableName);
    }

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void listsNewestUserScopedMemoriesAndHonorsLimit() {
        Instant base = Instant.parse("2026-09-10T20:00:00Z");
        LongTermMemoryStore.Memory oldest = memory("user-1", "oldest", base.plusSeconds(1));
        LongTermMemoryStore.Memory middle = memory("user-1", "middle", base.plusSeconds(2));
        LongTermMemoryStore.Memory newest = memory("user-1", "newest", base.plusSeconds(3));
        store.upsert(oldest);
        store.upsert(middle);
        store.upsert(newest);
        store.upsert(memory("user-2", "other-user", base.plusSeconds(4)));

        assertThat(store.list("user-1", 2))
                .extracting(LongTermMemoryStore.Memory::content)
                .containsExactly("newest", "middle");
    }

    @Test
    void upsertMovesExistingMemoryIndexWithoutLeavingDuplicateHistory() {
        Instant base = Instant.parse("2026-09-10T20:00:00Z");
        UUID id = UUID.randomUUID();
        store.upsert(new LongTermMemoryStore.Memory(id, "user-1", "before", base.plusSeconds(1)));
        store.upsert(memory("user-1", "other", base.plusSeconds(2)));
        store.upsert(new LongTermMemoryStore.Memory(id, "user-1", "after", base.plusSeconds(3)));

        assertThat(store.list("user-1", 10))
                .extracting(LongTermMemoryStore.Memory::id, LongTermMemoryStore.Memory::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(id, "after"),
                        org.assertj.core.groups.Tuple.tuple(store.list("user-1", 10).get(1).id(), "other"));
        assertThat(store.list("user-1", 10)).hasSize(2);
    }

    private LongTermMemoryStore.Memory memory(String userId, String content, Instant updatedAt) {
        return new LongTermMemoryStore.Memory(UUID.randomUUID(), userId, content, updatedAt);
    }
}
