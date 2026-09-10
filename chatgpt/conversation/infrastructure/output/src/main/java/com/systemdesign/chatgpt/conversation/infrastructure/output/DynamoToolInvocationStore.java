package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ToolInvocationStore;
import com.systemdesign.chatgpt.conversation.domain.ToolResult;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DynamoToolInvocationStore implements ToolInvocationStore {
    private static final String META = "META";
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoToolInvocationStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
        this.tableName = tableName;
    }

    @Override
    public Claim claim(UUID generationId, UUID callId, String toolName, String requestFingerprint,
            Instant now, Instant leaseUntil) {
        Objects.requireNonNull(generationId); Objects.requireNonNull(callId); Objects.requireNonNull(now); Objects.requireNonNull(leaseUntil);
        Map<String, AttributeValue> current = get(generationId, callId);
        Claim existing = inspect(current, toolName, requestFingerprint, now);
        if (existing != null) return existing;

        UUID token = UUID.randomUUID();
        Map<String, AttributeValue> item = claimedItem(generationId, callId, toolName, requestFingerprint, token, leaseUntil);
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(pk) OR (#status = :claimed AND leaseUntil <= :now)")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":claimed", string("CLAIMED"),
                            ":now", number(now.toEpochMilli())))
                    .build());
            return Claim.claimed(token);
        } catch (ConditionalCheckFailedException race) {
            current = get(generationId, callId);
            Claim raced = inspect(current, toolName, requestFingerprint, now);
            return raced == null ? Claim.busy() : raced;
        }
    }

    @Override
    public void complete(UUID generationId, UUID callId, UUID claimToken, ToolResult result, Instant completedAt) {
        Objects.requireNonNull(claimToken); Objects.requireNonNull(result); Objects.requireNonNull(completedAt);
        Map<String, AttributeValue> current = get(generationId, callId);
        if (current.isEmpty()) throw new IllegalStateException("tool invocation not found");
        Map<String, AttributeValue> item = new HashMap<>(current);
        item.put("status", string("COMPLETED"));
        item.put("resultStatus", string(result.status().name()));
        item.put("resultContent", string(result.content()));
        item.put("completedAt", number(completedAt.toEpochMilli()));
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.copyOf(item))
                    .conditionExpression("#status = :claimed AND claimToken = :token")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":claimed", string("CLAIMED"),
                            ":token", string(claimToken.toString())))
                    .build());
        } catch (ConditionalCheckFailedException stale) {
            throw new IllegalStateException("stale tool invocation claim", stale);
        }
    }

    private Claim inspect(Map<String, AttributeValue> item, String toolName, String requestFingerprint, Instant now) {
        if (item == null || item.isEmpty()) return null;
        if (!toolName.equals(item.get("toolName").s()) || !requestFingerprint.equals(item.get("requestFingerprint").s())) {
            throw new IllegalStateException("tool call id reused with different request");
        }
        if ("COMPLETED".equals(item.get("status").s())) {
            ToolResult result = new ToolResult(
                    UUID.fromString(item.get("callId").s()),
                    ToolResult.Status.valueOf(item.get("resultStatus").s()),
                    item.get("resultContent").s());
            return Claim.completed(result);
        }
        Instant leaseUntil = Instant.ofEpochMilli(Long.parseLong(item.get("leaseUntil").n()));
        return leaseUntil.isAfter(now) ? Claim.busy() : null;
    }

    private Map<String, AttributeValue> claimedItem(UUID generationId, UUID callId, String toolName,
            String requestFingerprint, UUID token, Instant leaseUntil) {
        return Map.of(
                "pk", string(pk(generationId, callId)),
                "sk", string(META),
                "entityType", string("TOOL_INVOCATION"),
                "generationId", string(generationId.toString()),
                "callId", string(callId.toString()),
                "toolName", string(toolName),
                "requestFingerprint", string(requestFingerprint),
                "status", string("CLAIMED"),
                "claimToken", string(token.toString()),
                "leaseUntil", number(leaseUntil.toEpochMilli()));
    }

    private Map<String, AttributeValue> get(UUID generationId, UUID callId) {
        return dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .key(Map.of("pk", string(pk(generationId, callId)), "sk", string(META)))
                .build()).item();
    }

    private static String pk(UUID generationId, UUID callId) { return "TOOLINV#" + generationId + "#" + callId; }
    private static AttributeValue string(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue number(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
}
