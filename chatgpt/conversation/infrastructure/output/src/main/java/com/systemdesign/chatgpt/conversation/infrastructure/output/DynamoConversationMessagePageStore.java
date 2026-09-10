package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ConversationMessagePageStore;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class DynamoConversationMessagePageStore implements ConversationMessagePageStore {
    private static final String META = "META";
    private static final String MESSAGE_PREFIX = "MSG#";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoConversationMessagePageStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
        this.tableName = tableName;
    }

    @Override
    public Page read(UUID conversationId, int limit, String cursor) {
        Objects.requireNonNull(conversationId, "conversationId");
        ensureConversationExists(conversationId);
        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", string(conversationPk(conversationId)),
                        ":prefix", string(MESSAGE_PREFIX)))
                .scanIndexForward(true)
                .limit(limit);
        String startSk = decode(cursor);
        if (startSk != null) {
            request.exclusiveStartKey(Map.of(
                    "pk", string(conversationPk(conversationId)),
                    "sk", string(startSk)));
        }

        QueryResponse response = dynamoDb.query(request.build());
        List<Message> messages = response.items().stream().map(this::messageFromItem).toList();
        Map<String, AttributeValue> lastKey = response.lastEvaluatedKey();
        String next = lastKey == null || lastKey.isEmpty() ? null : encode(lastKey.get("sk").s());
        return new Page(messages, next);
    }

    private void ensureConversationExists(UUID conversationId) {
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .consistentRead(true)
                        .key(Map.of(
                                "pk", string(conversationPk(conversationId)),
                                "sk", string(META)))
                        .build())
                .item();
        if (item == null || item.isEmpty()) {
            throw new NoSuchElementException("conversation not found: " + conversationId);
        }
    }

    private Message messageFromItem(Map<String, AttributeValue> item) {
        return new Message(
                UUID.fromString(item.get("messageId").s()),
                MessageRole.valueOf(item.get("role").s()),
                item.get("content").s(),
                Instant.ofEpochMilli(Long.parseLong(item.get("createdAt").n())));
    }

    private String encode(String sortKey) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sortKey.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!value.startsWith(MESSAGE_PREFIX)) throw new IllegalArgumentException("invalid message cursor");
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid message cursor", exception);
        }
    }

    private static String conversationPk(UUID conversationId) { return "CONV#" + conversationId; }
    private static AttributeValue string(String value) { return AttributeValue.builder().s(value).build(); }
}
