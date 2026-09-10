package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationConversationStore;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DynamoGenerationConversationStore implements GenerationConversationStore {
    private static final String META = "META";
    private static final String MESSAGE_PREFIX = "MSG#";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoGenerationConversationStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
        this.tableName = tableName;
    }

    @Override
    public Optional<Conversation> load(Generation generation, int maxHistoryMessages) {
        Objects.requireNonNull(generation, "generation");
        if (maxHistoryMessages < 1) throw new IllegalArgumentException("maxHistoryMessages must be >= 1");

        UUID conversationId = generation.conversationId();
        Map<String, AttributeValue> meta = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .key(Map.of("pk", string(conversationPk(conversationId)), "sk", string(META)))
                .build()).item();
        if (meta == null || meta.isEmpty()) return Optional.empty();

        String targetSk = messageSk(generation.createdAt(), generation.userMessageId());
        List<Map<String, AttributeValue>> items = dynamoDb.query(QueryRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .keyConditionExpression("pk = :pk AND sk BETWEEN :first AND :target")
                .expressionAttributeValues(Map.of(
                        ":pk", string(conversationPk(conversationId)),
                        ":first", string(MESSAGE_PREFIX),
                        ":target", string(targetSk)))
                .scanIndexForward(false)
                .limit(maxHistoryMessages)
                .build()).items();

        List<Message> messages = new ArrayList<>(items.stream().map(this::messageFromItem).toList());
        Collections.reverse(messages);
        if (messages.stream().noneMatch(message -> message.id().equals(generation.userMessageId()))) {
            throw new IllegalStateException("generation references missing user message: " + generation.userMessageId());
        }
        return Optional.of(Conversation.rehydrate(
                conversationId,
                meta.get("userId").s(),
                Instant.ofEpochMilli(Long.parseLong(meta.get("createdAt").n())),
                messages));
    }

    private Message messageFromItem(Map<String, AttributeValue> item) {
        AttributeValue generationId = item.get("generationId");
        return new Message(
                UUID.fromString(item.get("messageId").s()),
                MessageRole.valueOf(item.get("role").s()),
                item.get("content").s(),
                Instant.ofEpochMilli(Long.parseLong(item.get("createdAt").n())),
                generationId == null || generationId.s().isBlank() ? null : UUID.fromString(generationId.s()));
    }

    private static String conversationPk(UUID conversationId) { return "CONV#" + conversationId; }
    private static String messageSk(Instant createdAt, UUID messageId) {
        return "MSG#%019d#%s".formatted(createdAt.toEpochMilli(), messageId);
    }
    private static AttributeValue string(String value) { return AttributeValue.builder().s(value).build(); }
}
