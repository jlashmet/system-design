package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ConversationListStore;
import com.systemdesign.chatgpt.conversation.domain.ConversationMetadataStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DynamoConversationListStore implements ConversationListStore {
    private static final String CONVERSATION_PREFIX = "CONV#";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoConversationListStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
        this.tableName = tableName;
    }

    @Override
    public Page list(String subjectId, int limit, String cursor) {
        if (subjectId == null || subjectId.isBlank()) throw new IllegalArgumentException("subjectId must not be blank");
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", string("USER#" + subjectId),
                        ":prefix", string(CONVERSATION_PREFIX)))
                .scanIndexForward(false)
                .limit(limit + 1);
        String startSk = decode(cursor);
        if (startSk != null) {
            request.exclusiveStartKey(Map.of(
                    "pk", string("USER#" + subjectId),
                    "sk", string(startSk)));
        }
        List<Map<String, AttributeValue>> items = dynamoDb.query(request.build()).items();
        boolean hasMore = items.size() > limit;
        List<Map<String, AttributeValue>> pageItems = items.stream().limit(limit).toList();
        List<ConversationMetadataStore.Metadata> conversations = pageItems.stream()
                .map(item -> new ConversationMetadataStore.Metadata(
                        UUID.fromString(item.get("conversationId").s()),
                        item.get("userId").s(),
                        Instant.ofEpochMilli(Long.parseLong(item.get("createdAt").n()))))
                .toList();
        String next = hasMore && !pageItems.isEmpty() ? encode(pageItems.getLast().get("sk").s()) : null;
        return new Page(conversations, next);
    }

    private String encode(String sk) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sk.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!value.startsWith(CONVERSATION_PREFIX)) throw new IllegalArgumentException("invalid conversation cursor");
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid conversation cursor", exception);
        }
    }

    private static AttributeValue string(String value) { return AttributeValue.builder().s(value).build(); }
}
