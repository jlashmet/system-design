package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ConversationMetadataStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DynamoConversationMetadataStore implements ConversationMetadataStore {
    private static final String META = "META";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoConversationMetadataStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        this.tableName = tableName;
    }

    @Override
    public Optional<Metadata> find(UUID conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .consistentRead(true)
                        .key(Map.of(
                                "pk", AttributeValue.builder().s("CONV#" + conversationId).build(),
                                "sk", AttributeValue.builder().s(META).build()))
                        .build())
                .item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Metadata(
                conversationId,
                item.get("userId").s(),
                Instant.ofEpochMilli(Long.parseLong(item.get("createdAt").n()))));
    }
}
