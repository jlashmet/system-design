package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DynamoConversationSummaryStore implements ConversationSummaryStore {
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoConversationSummaryStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        this.tableName = tableName;
    }

    @Override
    public Optional<Summary> find(UUID conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .consistentRead(true)
                        .key(Map.of("pk", string(conversationId.toString())))
                        .build())
                .item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Summary(
                UUID.fromString(item.get("summaryId").s()),
                UUID.fromString(item.get("conversationId").s()),
                UUID.fromString(item.get("throughMessageId").s()),
                item.get("content").s(),
                Instant.ofEpochMilli(Long.parseLong(item.get("updatedAt").n()))));
    }

    @Override
    public void save(Summary summary) {
        Objects.requireNonNull(summary, "summary");
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            "pk", string(summary.conversationId().toString()),
                            "summaryId", string(summary.id().toString()),
                            "conversationId", string(summary.conversationId().toString()),
                            "throughMessageId", string(summary.throughMessageId().toString()),
                            "content", string(summary.content()),
                            "updatedAt", number(summary.updatedAt().toEpochMilli())))
                    .conditionExpression("attribute_not_exists(pk) OR updatedAt <= :updatedAt")
                    .expressionAttributeValues(Map.of(
                            ":updatedAt", number(summary.updatedAt().toEpochMilli())))
                    .build());
        } catch (ConditionalCheckFailedException ignored) {
            // A delayed refresh must never replace a newer summary snapshot.
        }
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
