package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.InferenceOutbox;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class DynamoOutboxTurnRepository implements TurnRepository, InferenceOutbox {
    private static final String META = "META";
    private static final int DEFAULT_SHARD_COUNT = 16;
    private static final int PAGE_SIZE = 20;

    private final TurnRepository delegate;
    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final int shardCount;
    private final AtomicInteger nextShard = new AtomicInteger();

    public DynamoOutboxTurnRepository(TurnRepository delegate, DynamoDbClient dynamoDb, String tableName) {
        this(delegate, dynamoDb, tableName, DEFAULT_SHARD_COUNT);
    }

    public DynamoOutboxTurnRepository(TurnRepository delegate, DynamoDbClient dynamoDb, String tableName, int shardCount) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
        if (shardCount < 1 || shardCount > 256) throw new IllegalArgumentException("shardCount must be between 1 and 256");
        this.tableName = tableName;
        this.shardCount = shardCount;
    }

    @Override public Optional<Generation> findGenerationById(UUID generationId) { return delegate.findGenerationById(generationId); }
    @Override public Optional<Generation> findByIdempotencyKey(UUID conversationId, String idempotencyKey) {
        return delegate.findByIdempotencyKey(conversationId, idempotencyKey);
    }

    @Override
    public BeginResult begin(Conversation conversation, Generation generation) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(generation, "generation");
        Message userMessage = conversation.messages().stream()
                .filter(message -> message.id().equals(generation.userMessageId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("generation references missing user message"));
        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(
                    put(messageItem(conversation.id(), userMessage), "attribute_not_exists(pk)"),
                    put(generationItem(generation), "attribute_not_exists(pk)"),
                    put(idempotencyItem(generation), "attribute_not_exists(pk)"),
                    put(outboxItem(generation), "attribute_not_exists(pk) AND attribute_not_exists(sk)"))
                    .build());
            return new BeginResult(generation, true);
        } catch (TransactionCanceledException exception) {
            Optional<Generation> existing = findByIdempotencyKey(generation.conversationId(), generation.idempotencyKey());
            if (existing.isPresent()) return new BeginResult(existing.get(), false);
            throw exception;
        }
    }

    @Override public Optional<Generation> claim(UUID generationId, Instant startedAt) { return delegate.claim(generationId, startedAt); }
    @Override public Optional<Generation> claim(UUID generationId, Instant startedAt, Instant leaseUntil) {
        return delegate.claim(generationId, startedAt, leaseUntil);
    }
    @Override public boolean renewClaim(UUID generationId, UUID claimToken, Instant renewedAt, Instant leaseUntil) {
        return delegate.renewClaim(generationId, claimToken, renewedAt, leaseUntil);
    }
    @Override public Optional<Generation> cancel(UUID generationId, Instant cancelledAt) { return delegate.cancel(generationId, cancelledAt); }
    @Override public void complete(Conversation conversation, Generation generation) { delegate.complete(conversation, generation); }
    @Override public void fail(Generation generation) { delegate.fail(generation); }

    @Override
    public Optional<Entry> claimNext(Instant claimedAt, Instant leaseUntil) {
        Objects.requireNonNull(claimedAt, "claimedAt");
        if (leaseUntil == null || !leaseUntil.isAfter(claimedAt)) throw new IllegalArgumentException("leaseUntil must be after claimedAt");
        int start = Math.floorMod(nextShard.getAndIncrement(), shardCount);
        for (int offset = 0; offset < shardCount; offset++) {
            Optional<Entry> claimed = claimFromShard((start + offset) % shardCount, claimedAt, leaseUntil);
            if (claimed.isPresent()) return claimed;
        }
        return Optional.empty();
    }

    private Optional<Entry> claimFromShard(int shard, Instant claimedAt, Instant leaseUntil) {
        Map<String, AttributeValue> exclusiveStartKey = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(tableName)
                    .consistentRead(true)
                    .keyConditionExpression("pk = :pk")
                    .expressionAttributeValues(Map.of(":pk", string(outboxPk(shard))))
                    .scanIndexForward(true)
                    .limit(PAGE_SIZE);
            if (exclusiveStartKey != null && !exclusiveStartKey.isEmpty()) request.exclusiveStartKey(exclusiveStartKey);
            var response = dynamoDb.query(request.build());
            for (Map<String, AttributeValue> current : response.items()) {
                String status = current.get("status").s();
                long lease = numberValue(current, "leaseUntil", 0L);
                if ("CLAIMED".equals(status) && lease > claimedAt.toEpochMilli()) continue;
                UUID generationId = UUID.fromString(current.get("generationId").s());
                UUID claimToken = UUID.randomUUID();
                Map<String, AttributeValue> claimed = new HashMap<>(current);
                claimed.put("status", string("CLAIMED"));
                claimed.put("claimToken", string(claimToken.toString()));
                claimed.put("leaseUntil", number(leaseUntil.toEpochMilli()));
                try {
                    dynamoDb.putItem(PutItemRequest.builder()
                            .tableName(tableName)
                            .item(claimed)
                            .conditionExpression("#status = :pending OR (#status = :claimed AND leaseUntil <= :now)")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(Map.of(
                                    ":pending", string("PENDING"),
                                    ":claimed", string("CLAIMED"),
                                    ":now", number(claimedAt.toEpochMilli())))
                            .build());
                    return Optional.of(new Entry(generationId, claimToken,
                            Instant.ofEpochMilli(Long.parseLong(current.get("createdAt").n())), leaseUntil));
                } catch (ConditionalCheckFailedException ignored) {
                    // Another dispatcher won this row. Continue through this page and subsequent pages.
                }
            }
            exclusiveStartKey = response.lastEvaluatedKey();
        } while (exclusiveStartKey != null && !exclusiveStartKey.isEmpty());
        return Optional.empty();
    }

    @Override
    public boolean markDispatched(UUID generationId, UUID claimToken, Instant dispatchedAt) {
        Generation generation = findGenerationById(generationId).orElse(null);
        if (generation == null) return false;
        String pk = outboxPk(generationId);
        String sk = outboxSk(generation.createdAt(), generation.id());
        try {
            dynamoDb.deleteItem(DeleteItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("pk", string(pk), "sk", string(sk)))
                    .conditionExpression("#status = :claimed AND claimToken = :token")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":claimed", string("CLAIMED"),
                            ":token", string(claimToken.toString())))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException exception) { return false; }
    }

    @Override
    public boolean release(UUID generationId, UUID claimToken) {
        Generation generation = findGenerationById(generationId).orElse(null);
        if (generation == null) return false;
        String pk = outboxPk(generationId);
        String sk = outboxSk(generation.createdAt(), generation.id());
        Map<String, AttributeValue> current = outboxByKey(pk, sk);
        if (current.isEmpty()) return false;
        Map<String, AttributeValue> released = new HashMap<>(current);
        released.put("status", string("PENDING"));
        released.remove("claimToken");
        released.remove("leaseUntil");
        try {
            dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(released)
                    .conditionExpression("#status = :claimed AND claimToken = :token")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":claimed", string("CLAIMED"),
                            ":token", string(claimToken.toString())))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException exception) { return false; }
    }

    private Map<String, AttributeValue> outboxByKey(String pk, String sk) {
        return dynamoDb.getItem(builder -> builder.tableName(tableName).consistentRead(true)
                .key(Map.of("pk", string(pk), "sk", string(sk)))).item();
    }

    private Map<String, AttributeValue> outboxItem(Generation generation) {
        return Map.of(
                "pk", string(outboxPk(generation.id())),
                "sk", string(outboxSk(generation.createdAt(), generation.id())),
                "entityType", string("INFERENCE_OUTBOX"),
                "generationId", string(generation.id().toString()),
                "status", string("PENDING"),
                "createdAt", number(generation.createdAt().toEpochMilli()));
    }

    private String outboxPk(UUID generationId) {
        return outboxPk(Math.floorMod(generationId.hashCode(), shardCount));
    }
    private static String outboxPk(int shard) { return "OUTBOX#INFERENCE#%03d".formatted(shard); }

    private Map<String, AttributeValue> messageItem(UUID conversationId, Message message) {
        return Map.of("pk", string("CONV#" + conversationId), "sk", string(messageSk(message)),
                "entityType", string("MESSAGE"), "messageId", string(message.id().toString()),
                "role", string(message.role().name()), "content", string(message.content()),
                "createdAt", number(message.createdAt().toEpochMilli()));
    }

    private Map<String, AttributeValue> generationItem(Generation generation) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", string("GEN#" + generation.id())); item.put("sk", string(META));
        item.put("entityType", string("GENERATION")); item.put("generationId", string(generation.id().toString()));
        item.put("conversationId", string(generation.conversationId().toString()));
        item.put("idempotencyKey", string(generation.idempotencyKey())); item.put("requestContent", string(generation.requestContent()));
        item.put("requiredCapabilities", AttributeValue.builder().l(generation.requiredCapabilities().stream().sorted()
                .map(ModelCapability::name).map(DynamoOutboxTurnRepository::string).toList()).build());
        item.put("userMessageId", string(generation.userMessageId().toString()));
        item.put("status", string(GenerationStatus.PENDING.name()));
        item.put("createdAt", number(generation.createdAt().toEpochMilli())); item.put("updatedAt", number(generation.updatedAt().toEpochMilli()));
        return Map.copyOf(item);
    }

    private Map<String, AttributeValue> idempotencyItem(Generation generation) {
        return Map.of("pk", string("IDEMP#" + generation.conversationId() + "#" + sha256(generation.idempotencyKey())),
                "sk", string(META), "entityType", string("IDEMPOTENCY"),
                "conversationId", string(generation.conversationId().toString()),
                "idempotencyKey", string(generation.idempotencyKey()), "generationId", string(generation.id().toString()));
    }

    private TransactWriteItem put(Map<String, AttributeValue> item, String condition) {
        return TransactWriteItem.builder().put(Put.builder().tableName(tableName).item(item).conditionExpression(condition).build()).build();
    }
    private static String outboxSk(Instant createdAt, UUID generationId) { return "%019d#%s".formatted(createdAt.toEpochMilli(), generationId); }
    private static String messageSk(Message message) { return "MSG#%019d#%s".formatted(message.createdAt().toEpochMilli(), message.id()); }
    private static long numberValue(Map<String, AttributeValue> item, String key, long fallback) {
        AttributeValue value = item.get(key); return value == null || value.n() == null ? fallback : Long.parseLong(value.n());
    }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static AttributeValue string(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue number(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
}
