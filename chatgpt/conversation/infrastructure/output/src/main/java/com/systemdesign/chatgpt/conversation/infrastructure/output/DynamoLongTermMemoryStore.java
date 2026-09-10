package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.LongTermMemoryStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DynamoLongTermMemoryStore implements LongTermMemoryStore {
    private static final String META = "META";
    private static final long REVERSE_TIME_BASE = 9_999_999_999_999L;

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoLongTermMemoryStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
        this.tableName = tableName;
    }

    @Override
    public List<Memory> list(String userId, int limit) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId must not be blank");
        if (limit < 0) throw new IllegalArgumentException("limit must be >= 0");
        if (limit == 0) return List.of();

        return dynamoDb.query(QueryRequest.builder()
                        .tableName(tableName)
                        .consistentRead(true)
                        .keyConditionExpression("pk = :pk")
                        .expressionAttributeValues(Map.of(":pk", string(userPk(userId))))
                        .limit(limit)
                        .scanIndexForward(true)
                        .build())
                .items().stream()
                .map(this::memoryFromItem)
                .toList();
    }

    @Override
    public void upsert(Memory memory) {
        Objects.requireNonNull(memory, "memory");
        Map<String, AttributeValue> existing = canonical(memory.id());
        List<TransactWriteItem> writes = new ArrayList<>();
        if (!existing.isEmpty()) {
            String oldUserId = existing.get("userId").s();
            Instant oldUpdatedAt = Instant.ofEpochMilli(Long.parseLong(existing.get("updatedAt").n()));
            String oldPk = userPk(oldUserId);
            String oldSk = indexSk(oldUpdatedAt, memory.id());
            String newPk = userPk(memory.userId());
            String newSk = indexSk(memory.updatedAt(), memory.id());
            if (!oldPk.equals(newPk) || !oldSk.equals(newSk)) {
                writes.add(TransactWriteItem.builder().delete(Delete.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", string(oldPk), "sk", string(oldSk)))
                        .build()).build());
            }
        }
        writes.add(TransactWriteItem.builder().put(Put.builder()
                .tableName(tableName)
                .item(canonicalItem(memory))
                .build()).build());
        writes.add(TransactWriteItem.builder().put(Put.builder()
                .tableName(tableName)
                .item(indexItem(memory))
                .build()).build());
        dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(writes).build());
    }

    private Map<String, AttributeValue> canonical(UUID memoryId) {
        return dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .consistentRead(true)
                        .key(Map.of("pk", string(memoryPk(memoryId)), "sk", string(META)))
                        .build())
                .item();
    }

    private Map<String, AttributeValue> canonicalItem(Memory memory) {
        return Map.of(
                "pk", string(memoryPk(memory.id())),
                "sk", string(META),
                "entityType", string("LONG_TERM_MEMORY"),
                "memoryId", string(memory.id().toString()),
                "userId", string(memory.userId()),
                "content", string(memory.content()),
                "updatedAt", number(memory.updatedAt().toEpochMilli()));
    }

    private Map<String, AttributeValue> indexItem(Memory memory) {
        return Map.of(
                "pk", string(userPk(memory.userId())),
                "sk", string(indexSk(memory.updatedAt(), memory.id())),
                "entityType", string("LONG_TERM_MEMORY_INDEX"),
                "memoryId", string(memory.id().toString()),
                "userId", string(memory.userId()),
                "content", string(memory.content()),
                "updatedAt", number(memory.updatedAt().toEpochMilli()));
    }

    private Memory memoryFromItem(Map<String, AttributeValue> item) {
        return new Memory(
                UUID.fromString(item.get("memoryId").s()),
                item.get("userId").s(),
                item.get("content").s(),
                Instant.ofEpochMilli(Long.parseLong(item.get("updatedAt").n())));
    }

    private static String indexSk(Instant updatedAt, UUID memoryId) {
        long reverse = REVERSE_TIME_BASE - updatedAt.toEpochMilli();
        return "MEM#%013d#%s".formatted(reverse, memoryId);
    }

    private static String userPk(String userId) { return "MEMUSER#" + sha256(userId); }
    private static String memoryPk(UUID memoryId) { return "MEMORY#" + memoryId; }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static AttributeValue string(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue number(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
}
