package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
import com.systemdesign.chatgpt.conversation.domain.GenerationEventStore;
import com.systemdesign.chatgpt.conversation.domain.ReplayableGenerationEventBus;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DynamoGenerationEventStore implements GenerationEventStore {
    private static final String META_SK = "META";
    private static final String EVENT_PREFIX = "EVENT#";
    private static final String EVENT_UPPER_BOUND = "EVENT#~";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoGenerationEventStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        this.tableName = tableName;
    }

    @Override
    public ReplayableGenerationEventBus.RecordedEvent append(UUID generationId, GenerationEventBus.Event event) {
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(event, "event");
        long sequence = allocateSequence(generationId);

        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", string(pk(generationId)),
                        "sk", string(eventSk(sequence)),
                        "sequence", number(sequence),
                        "type", string(event.type().name()),
                        "data", string(event.data())))
                .conditionExpression("attribute_not_exists(pk) AND attribute_not_exists(sk)")
                .build());
        return new ReplayableGenerationEventBus.RecordedEvent(sequence, event);
    }

    @Override
    public List<ReplayableGenerationEventBus.RecordedEvent> listAfter(
            UUID generationId,
            long afterSequence,
            int limit) {
        Objects.requireNonNull(generationId, "generationId");
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be >= 0");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }

        var response = dynamoDb.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("pk = :pk AND sk BETWEEN :from AND :to")
                .expressionAttributeValues(Map.of(
                        ":pk", string(pk(generationId)),
                        ":from", string(eventSk(afterSequence + 1)),
                        ":to", string(EVENT_UPPER_BOUND)))
                .scanIndexForward(true)
                .limit(limit)
                .build());

        return response.items().stream().map(this::toRecordedEvent).toList();
    }

    private long allocateSequence(UUID generationId) {
        var response = dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("pk", string(pk(generationId)), "sk", string(META_SK)))
                .updateExpression("ADD nextSequence :one")
                .expressionAttributeValues(Map.of(":one", number(1)))
                .returnValues(ReturnValue.UPDATED_NEW)
                .build());
        return Long.parseLong(response.attributes().get("nextSequence").n());
    }

    private ReplayableGenerationEventBus.RecordedEvent toRecordedEvent(Map<String, AttributeValue> item) {
        long sequence = Long.parseLong(item.get("sequence").n());
        GenerationEventBus.Type type = GenerationEventBus.Type.valueOf(item.get("type").s());
        String data = item.get("data").s();
        return new ReplayableGenerationEventBus.RecordedEvent(
                sequence,
                new GenerationEventBus.Event(type, data));
    }

    private static String pk(UUID generationId) {
        return "GENERATION#" + generationId;
    }

    private static String eventSk(long sequence) {
        return EVENT_PREFIX + String.format("%020d", sequence);
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
