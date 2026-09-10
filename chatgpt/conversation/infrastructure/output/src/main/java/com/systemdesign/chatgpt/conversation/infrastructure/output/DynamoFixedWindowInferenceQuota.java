package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.InferenceQuota;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

public final class DynamoFixedWindowInferenceQuota implements InferenceQuota {
    private static final String COUNTER = "COUNTER";

    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final int maxRequests;
    private final long windowMillis;

    public DynamoFixedWindowInferenceQuota(
            DynamoDbClient dynamoDb,
            String tableName,
            int maxRequests,
            Duration window) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
        if (maxRequests < 1) throw new IllegalArgumentException("maxRequests must be >= 1");
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) throw new IllegalArgumentException("window must be > 0");
        this.tableName = tableName;
        this.maxRequests = maxRequests;
        this.windowMillis = window.toMillis();
    }

    @Override
    public boolean tryAcquire(String subjectId, String idempotencyKey, Instant now) {
        if (subjectId == null || subjectId.isBlank()) throw new IllegalArgumentException("subjectId must not be blank");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
        Objects.requireNonNull(now, "now");

        long windowStart = Math.floorDiv(now.toEpochMilli(), windowMillis) * windowMillis;
        String pk = "QUOTA#" + sha256(subjectId) + "#" + windowStart;
        String idempotencySk = "IDEMP#" + sha256(idempotencyKey);
        if (exists(pk, idempotencySk)) return true;

        long expiresAt = Instant.ofEpochMilli(windowStart + (windowMillis * 2)).getEpochSecond();
        Put admission = Put.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", string(pk),
                        "sk", string(idempotencySk),
                        "entityType", string("QUOTA_ADMISSION"),
                        "expiresAt", number(expiresAt)))
                .conditionExpression("attribute_not_exists(pk) AND attribute_not_exists(sk)")
                .build();
        Update counter = Update.builder()
                .tableName(tableName)
                .key(Map.of("pk", string(pk), "sk", string(COUNTER)))
                .updateExpression("SET #count = if_not_exists(#count, :zero) + :one, expiresAt = :expiresAt")
                .conditionExpression("attribute_not_exists(#count) OR #count < :limit")
                .expressionAttributeNames(Map.of("#count", "requestCount"))
                .expressionAttributeValues(Map.of(
                        ":zero", number(0),
                        ":one", number(1),
                        ":limit", number(maxRequests),
                        ":expiresAt", number(expiresAt)))
                .build();

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            TransactWriteItem.builder().put(admission).build(),
                            TransactWriteItem.builder().update(counter).build())
                    .build());
            return true;
        } catch (TransactionCanceledException exception) {
            return exists(pk, idempotencySk);
        }
    }

    private boolean exists(String pk, String sk) {
        return !dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .consistentRead(true)
                        .key(Map.of("pk", string(pk), "sk", string(sk)))
                        .build())
                .item().isEmpty();
    }

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
