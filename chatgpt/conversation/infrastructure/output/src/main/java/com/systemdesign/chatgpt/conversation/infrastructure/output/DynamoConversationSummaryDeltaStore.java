package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryDeltaStore;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DynamoConversationSummaryDeltaStore implements ConversationSummaryDeltaStore {
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoConversationSummaryDeltaStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
        this.tableName = tableName;
    }

    @Override
    public List<Message> load(UUID conversationId, Position afterExclusive, Position throughInclusive, int limit) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(afterExclusive, "afterExclusive");
        Objects.requireNonNull(throughInclusive, "throughInclusive");
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        if (throughInclusive.compareTo(afterExclusive) <= 0) return List.of();

        String afterSk = messageSk(afterExclusive);
        String throughSk = messageSk(throughInclusive);
        List<Message> result = new ArrayList<>(Math.min(limit, 256));
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(tableName)
                    .consistentRead(true)
                    .keyConditionExpression("pk = :pk AND sk BETWEEN :after AND :through")
                    .expressionAttributeValues(Map.of(
                            ":pk", string("CONV#" + conversationId),
                            ":after", string(afterSk),
                            ":through", string(throughSk)))
                    .scanIndexForward(true)
                    .limit(Math.min(limit + 1, 256));
            if (startKey != null && !startKey.isEmpty()) request.exclusiveStartKey(startKey);
            var response = dynamoDb.query(request.build());
            for (Map<String, AttributeValue> item : response.items()) {
                if (afterSk.equals(item.get("sk").s())) continue;
                if (!"MESSAGE".equals(item.getOrDefault("entityType", string("")).s())) continue;
                result.add(messageFromItem(item));
                if (result.size() == limit) return List.copyOf(result);
            }
            startKey = response.lastEvaluatedKey();
        } while (startKey != null && !startKey.isEmpty());
        return List.copyOf(result);
    }

    private Message messageFromItem(Map<String, AttributeValue> item) {
        return new Message(
                UUID.fromString(item.get("messageId").s()),
                MessageRole.valueOf(item.get("role").s()),
                item.get("content").s(),
                Instant.ofEpochMilli(Long.parseLong(item.get("createdAt").n())));
    }

    private static String messageSk(Position position) {
        return "MSG#%019d#%s".formatted(position.createdAt().toEpochMilli(), position.messageId());
    }

    private static AttributeValue string(String value) { return AttributeValue.builder().s(value).build(); }
}
