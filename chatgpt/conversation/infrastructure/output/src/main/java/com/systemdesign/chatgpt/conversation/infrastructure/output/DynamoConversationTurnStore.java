package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.RunningMessageStore;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class DynamoConversationTurnStore implements ConversationRepository, TurnRepository, RunningMessageStore {
    private static final String META = "META";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoConversationTurnStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        this.tableName = tableName;
    }

    @Override
    public Optional<Conversation> findById(UUID conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        List<Map<String, AttributeValue>> items = dynamoDb.query(QueryRequest.builder()
                        .tableName(tableName)
                        .consistentRead(true)
                        .keyConditionExpression("pk = :pk")
                        .expressionAttributeValues(Map.of(":pk", string(conversationPk(conversationId))))
                        .build())
                .items();
        Map<String, AttributeValue> meta = items.stream()
                .filter(item -> META.equals(item.get("sk").s()))
                .findFirst()
                .orElse(null);
        if (meta == null) {
            return Optional.empty();
        }
        List<Message> messages = items.stream()
                .filter(item -> item.get("sk").s().startsWith("MSG#"))
                .map(this::messageFromItem)
                .sorted(Comparator.comparing(Message::createdAt).thenComparing(Message::id))
                .toList();
        return Optional.of(Conversation.rehydrate(
                conversationId,
                meta.get("userId").s(),
                instant(meta, "createdAt"),
                messages));
    }

    @Override
    public void save(Conversation conversation) {
        Objects.requireNonNull(conversation, "conversation");
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(conversationMeta(conversation))
                .build());
        for (Message message : conversation.messages()) {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(messageItem(conversation.id(), message))
                    .build());
        }
    }

    @Override
    public Optional<Generation> findGenerationById(UUID generationId) {
        Objects.requireNonNull(generationId, "generationId");
        Map<String, AttributeValue> item = get(generationPk(generationId), META);
        return item.isEmpty() ? Optional.empty() : Optional.of(generationFromItem(item));
    }

    @Override
    public Optional<Generation> findByIdempotencyKey(UUID conversationId, String idempotencyKey) {
        Objects.requireNonNull(conversationId, "conversationId");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Map<String, AttributeValue> mapping = get(idempotencyPk(conversationId, idempotencyKey), META);
        if (mapping.isEmpty() || !idempotencyKey.equals(mapping.get("idempotencyKey").s())) {
            return Optional.empty();
        }
        return findGenerationById(UUID.fromString(mapping.get("generationId").s()));
    }

    @Override
    public BeginResult begin(Conversation conversation, Generation generation) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(generation, "generation");
        Message userMessage = conversation.messages().stream()
                .filter(message -> message.id().equals(generation.userMessageId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("generation references missing user message"));

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            put(messageItem(conversation.id(), userMessage), "attribute_not_exists(pk)"),
                            put(generationItem(generation), "attribute_not_exists(pk)"),
                            put(idempotencyItem(generation), "attribute_not_exists(pk)"))
                    .build());
            return new BeginResult(generation, true);
        } catch (TransactionCanceledException exception) {
            Optional<Generation> existing = findByIdempotencyKey(
                    generation.conversationId(), generation.idempotencyKey());
            if (existing.isPresent()) {
                return new BeginResult(existing.get(), false);
            }
            throw exception;
        }
    }

    @Override
    public Optional<Generation> claim(UUID generationId, Instant startedAt) {
        Generation current = findGenerationById(generationId).orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        Generation running = current.running(startedAt);
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(generationItem(running))
                    .conditionExpression("#status = :pending OR #status = :failed")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":pending", string(GenerationStatus.PENDING.name()),
                            ":failed", string(GenerationStatus.FAILED.name())))
                    .build());
            return Optional.of(running);
        } catch (ConditionalCheckFailedException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Generation> cancel(UUID generationId, Instant cancelledAt) {
        Generation current = findGenerationById(generationId).orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        if (current.status() == GenerationStatus.COMPLETED || current.status() == GenerationStatus.CANCELLED) {
            return Optional.of(current);
        }
        Generation cancelled = current.cancelled(cancelledAt);
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(generationItem(cancelled))
                    .conditionExpression("#status = :pending OR #status = :running OR #status = :failed")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":pending", string(GenerationStatus.PENDING.name()),
                            ":running", string(GenerationStatus.RUNNING.name()),
                            ":failed", string(GenerationStatus.FAILED.name())))
                    .build());
            return Optional.of(cancelled);
        } catch (ConditionalCheckFailedException exception) {
            return findGenerationById(generationId);
        }
    }

    @Override
    public boolean append(UUID generationId, List<Message> messages) {
        Objects.requireNonNull(generationId, "generationId");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (messages.isEmpty()) {
            return true;
        }
        if (messages.size() > 99) {
            throw new IllegalArgumentException("a running transcript append may contain at most 99 messages");
        }
        Generation generation = findGenerationById(generationId).orElse(null);
        if (generation == null || generation.status() != GenerationStatus.RUNNING) {
            return false;
        }

        List<TransactWriteItem> writes = new ArrayList<>(messages.size() + 1);
        writes.add(TransactWriteItem.builder()
                .conditionCheck(ConditionCheck.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", string(generationPk(generationId)), "sk", string(META)))
                        .conditionExpression("#status = :running")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(Map.of(":running", string(GenerationStatus.RUNNING.name())))
                        .build())
                .build());
        for (Message message : messages) {
            writes.add(put(messageItem(generation.conversationId(), message), "attribute_not_exists(pk)"));
        }
        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(writes).build());
            return true;
        } catch (TransactionCanceledException exception) {
            Generation current = findGenerationById(generationId).orElse(null);
            if (current == null || current.status() != GenerationStatus.RUNNING) {
                return false;
            }
            throw exception;
        }
    }

    @Override
    public void complete(Conversation conversation, Generation generation) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(generation, "generation");
        if (generation.status() != GenerationStatus.COMPLETED || generation.assistantMessageId() == null) {
            throw new IllegalArgumentException("complete requires a completed generation");
        }
        Message assistant = conversation.messages().stream()
                .filter(message -> message.id().equals(generation.assistantMessageId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("completed generation references missing assistant message"));
        Put generationPut = Put.builder()
                .tableName(tableName)
                .item(generationItem(generation))
                .conditionExpression("#status = :running")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":running", string(GenerationStatus.RUNNING.name())))
                .build();
        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            put(messageItem(conversation.id(), assistant), "attribute_not_exists(pk)"),
                            TransactWriteItem.builder().put(generationPut).build())
                    .build());
        } catch (TransactionCanceledException exception) {
            Generation current = findGenerationById(generation.id()).orElseThrow(() -> exception);
            if (current.status() != GenerationStatus.CANCELLED && current.status() != GenerationStatus.COMPLETED) {
                throw exception;
            }
        }
    }

    @Override
    public void fail(Generation generation) {
        Objects.requireNonNull(generation, "generation");
        if (generation.status() != GenerationStatus.FAILED) {
            throw new IllegalArgumentException("fail requires a failed generation");
        }
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(generationItem(generation))
                    .conditionExpression("#status = :running")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(":running", string(GenerationStatus.RUNNING.name())))
                    .build());
        } catch (ConditionalCheckFailedException exception) {
            Generation current = findGenerationById(generation.id()).orElseThrow(() -> exception);
            if (current.status() != GenerationStatus.CANCELLED && current.status() != GenerationStatus.FAILED) {
                throw exception;
            }
        }
    }

    private Map<String, AttributeValue> conversationMeta(Conversation conversation) {
        return Map.of(
                "pk", string(conversationPk(conversation.id())),
                "sk", string(META),
                "entityType", string("CONVERSATION"),
                "userId", string(conversation.userId()),
                "createdAt", number(conversation.createdAt().toEpochMilli()));
    }

    private Map<String, AttributeValue> messageItem(UUID conversationId, Message message) {
        return Map.of(
                "pk", string(conversationPk(conversationId)),
                "sk", string(messageSk(message)),
                "entityType", string("MESSAGE"),
                "messageId", string(message.id().toString()),
                "role", string(message.role().name()),
                "content", string(message.content()),
                "createdAt", number(message.createdAt().toEpochMilli()));
    }

    private Map<String, AttributeValue> generationItem(Generation generation) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", string(generationPk(generation.id())));
        item.put("sk", string(META));
        item.put("entityType", string("GENERATION"));
        item.put("generationId", string(generation.id().toString()));
        item.put("conversationId", string(generation.conversationId().toString()));
        item.put("idempotencyKey", string(generation.idempotencyKey()));
        item.put("requestContent", string(generation.requestContent()));
        item.put("requiredCapabilities", AttributeValue.builder().l(generation.requiredCapabilities().stream()
                .sorted()
                .map(capability -> string(capability.name()))
                .toList()).build());
        item.put("userMessageId", string(generation.userMessageId().toString()));
        if (generation.assistantMessageId() != null) {
            item.put("assistantMessageId", string(generation.assistantMessageId().toString()));
        }
        item.put("status", string(generation.status().name()));
        item.put("createdAt", number(generation.createdAt().toEpochMilli()));
        item.put("updatedAt", number(generation.updatedAt().toEpochMilli()));
        return Map.copyOf(item);
    }

    private Map<String, AttributeValue> idempotencyItem(Generation generation) {
        return Map.of(
                "pk", string(idempotencyPk(generation.conversationId(), generation.idempotencyKey())),
                "sk", string(META),
                "entityType", string("IDEMPOTENCY"),
                "conversationId", string(generation.conversationId().toString()),
                "idempotencyKey", string(generation.idempotencyKey()),
                "generationId", string(generation.id().toString()));
    }

    private Generation generationFromItem(Map<String, AttributeValue> item) {
        Set<ModelCapability> capabilities = item.getOrDefault(
                        "requiredCapabilities", AttributeValue.builder().l(List.of()).build())
                .l().stream()
                .map(AttributeValue::s)
                .map(ModelCapability::valueOf)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        AttributeValue assistant = item.get("assistantMessageId");
        return new Generation(
                UUID.fromString(item.get("generationId").s()),
                UUID.fromString(item.get("conversationId").s()),
                item.get("idempotencyKey").s(),
                item.get("requestContent").s(),
                capabilities,
                UUID.fromString(item.get("userMessageId").s()),
                assistant == null ? null : UUID.fromString(assistant.s()),
                GenerationStatus.valueOf(item.get("status").s()),
                instant(item, "createdAt"),
                instant(item, "updatedAt"));
    }

    private Message messageFromItem(Map<String, AttributeValue> item) {
        return new Message(
                UUID.fromString(item.get("messageId").s()),
                MessageRole.valueOf(item.get("role").s()),
                item.get("content").s(),
                instant(item, "createdAt"));
    }

    private Map<String, AttributeValue> get(String pk, String sk) {
        return dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .consistentRead(true)
                        .key(Map.of("pk", string(pk), "sk", string(sk)))
                        .build())
                .item();
    }

    private TransactWriteItem put(Map<String, AttributeValue> item, String conditionExpression) {
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(item)
                        .conditionExpression(conditionExpression)
                        .build())
                .build();
    }

    private static String conversationPk(UUID conversationId) {
        return "CONV#" + conversationId;
    }

    private static String generationPk(UUID generationId) {
        return "GEN#" + generationId;
    }

    private static String idempotencyPk(UUID conversationId, String idempotencyKey) {
        return "IDEMP#" + conversationId + "#" + sha256(idempotencyKey);
    }

    private static String messageSk(Message message) {
        return "MSG#%019d#%s".formatted(message.createdAt().toEpochMilli(), message.id());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static Instant instant(Map<String, AttributeValue> item, String name) {
        return Instant.ofEpochMilli(Long.parseLong(item.get(name).n()));
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
