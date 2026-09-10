package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DynamoGenerationEventStoreIT {
    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private String tableName;
    private DynamoGenerationEventStore store;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "chatgpt-generation-events-" + UUID.randomUUID();
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
        store = new DynamoGenerationEventStore(dynamoDb, tableName);
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
    void allocatesMonotonicSequenceAndListsAfterCursor() {
        UUID generationId = UUID.randomUUID();

        var one = store.append(generationId, GenerationEventBus.Event.delta("one"));
        var two = store.append(generationId, GenerationEventBus.Event.delta("two"));
        var three = store.append(generationId, GenerationEventBus.Event.completed());

        assertThat(one.sequence()).isEqualTo(1L);
        assertThat(two.sequence()).isEqualTo(2L);
        assertThat(three.sequence()).isEqualTo(3L);
        assertThat(store.latestSequence(generationId)).isEqualTo(3L);
        assertThat(store.listAfter(generationId, 1L, 100))
                .extracting(recorded -> recorded.sequence(), recorded -> recorded.event().type(), recorded -> recorded.event().data())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2L, GenerationEventBus.Type.DELTA, "two"),
                        org.assertj.core.groups.Tuple.tuple(3L, GenerationEventBus.Type.COMPLETED, ""));
    }

    @Test
    void maintainsIndependentSequencePerGenerationAndHonorsLimit() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.append(first, GenerationEventBus.Event.delta("a1"));
        store.append(first, GenerationEventBus.Event.delta("a2"));
        store.append(second, GenerationEventBus.Event.delta("b1"));

        assertThat(store.latestSequence(first)).isEqualTo(2L);
        assertThat(store.latestSequence(second)).isEqualTo(1L);
        assertThat(store.listAfter(first, 0L, 1))
                .extracting(recorded -> recorded.sequence())
                .containsExactly(1L);
        assertThat(store.listAfter(second, 0L, 100))
                .extracting(recorded -> recorded.event().data())
                .containsExactly("b1");
    }
}
