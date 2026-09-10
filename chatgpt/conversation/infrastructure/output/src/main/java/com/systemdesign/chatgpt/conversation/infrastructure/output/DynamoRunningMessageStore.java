package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.GenerationContinuationStore;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.RunningMessageStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DynamoRunningMessageStore implements RunningMessageStore, GenerationContinuationStore {
    private static final String META = "META";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoRunningMessageStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
        this.tableName = tableName;
    }

    @Override
    public boolean append(UUID generationId, List<Message> messages) {
        Objects.requireNonNull(generationId, "generationId");
        List<Message> copy = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (copy.isEmpty()) return true;
        if (copy.size() > 49) throw new IllegalArgumentException("a continuation append may contain at most 49 messages");

        Map<String, AttributeValue> generation = generationItem(generationId);
        if (generation.isEmpty()) return false;
        UUID conversationId = UUID.fromString(generation.get("conversationId").s());

        List<TransactWriteItem> items = new ArrayList<>(1 + copy.size() * 2);
        items.add(TransactWriteItem.builder()
                .conditionCheck(ConditionCheck.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", string(generationPk(generationId)), "sk", string(META)))
                        .conditionExpression("#status = :running")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(Map.of(":running", string(GenerationStatus.RUNNING.name())))
                        .build())
                .build());
        for (Message message : copy) {
            items.add(put(messageItem(conversationId, message)));
            items.add(put(continuationItem(generationId, message)));
        }

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(items).build());
            return true;
        } catch (TransactionCanceledException exception) {
            Map<String, AttributeValue> current = generationItem(generationId);
            if (current.isEmpty() || !GenerationStatus.RUNNING.name().equals(current.get("status").s())) return false;
            throw exception;
        }
    }

    @Override
    public List<Message> list(UUID generationId) {
        Objects.requireNonNull(generationId, "generationId");
        return dynamoDb.query(QueryRequest.builder()
                        .tableName(tableName)
                        .consistentRead(true)
                        .keyConditionExpression("pk = :pk")
                        .expressionAttributeValues(Map.of(":pk", string(continuationPk(generationId))))
                        .scanIndexForward(true)
                        .build())
                .items().stream()
                .map(this::messageFromItem)
                .toList();
    }

    private Map<String, AttributeValue> generationItem(UUID generationId) {
        return dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .key(Map.of("pk", string(generationPk(generationId)), "sk", string(META)))
                .build()).item();
    }

    private TransactWriteItem put(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(item)
                        .conditionExpression("attribute_not_exists(pk) AND attribute_not_exists(sk)")
                        .build())
                .build();
    }

    private Map<String, AttributeValue> messageItem(UUID conversationId, Message message) {
        return messageItem(conversationPk(conversationId), message);
    }

    private Map<String, AttributeValue> continuationItem(UUID generationId, Message message) {
        return messageItem(continuationPk(generationId), message);
    }

    private Map<String, AttributeValue> messageItem(String pk, Message message) {
        return Map.of(
                "pk", string(pk),
                "sk", string(messageSk(message)),
                "entityType", string("MESSAGE"),
                "messageId", string(message.id().toString()),
                "role", string(message.role().name()),
                "content", string(message.content()),
                "createdAt", number(message.createdAt().toEpochMilli()),
                "generationId", string(message.generationId() == null ? "" : message.generationId().toString()));
    }

    private Message messageFromItem(Map<String, AttributeValue> item) {
        String generationId = item.getOrDefault("generationId", string("")).s();
        return new Message(
                UUID.fromString(item.get("messageId").s()),
                MessageRole.valueOf(item.get("role").s()),
                item.get("content").s(),
                Instant.ofEpochMilli(Long.parseLong(item.get("createdAt").n())),
                generationId.isBlank() ? null : UUID.fromString(generationId));
    }

    private static String conversationPk(UUID conversationId) { return "CONV#" + conversationId; }
    private static String generationPk(UUID generationId) { return "GEN#" + generationId; }
    private static String continuationPk(UUID generationId) { return "GENCONT#" + generationId; }
    private static String messageSk(Message message) {
        return "MSG#%019d#%s".formatted(message.createdAt().toEpochMilli(), message.id());
    }
    private static AttributeValue string(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue number(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
}
