package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DynamoGenerationLeaseTurnRepository implements TurnRepository {
    private static final String META = "META";

    private final TurnRepository delegate;
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoGenerationLeaseTurnRepository(
            TurnRepository delegate,
            DynamoDbClient dynamoDb,
            String tableName) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        this.tableName = tableName;
    }

    @Override
    public Optional<Generation> findGenerationById(UUID generationId) {
        return delegate.findGenerationById(generationId);
    }

    @Override
    public Optional<Generation> findByIdempotencyKey(UUID conversationId, String idempotencyKey) {
        return delegate.findByIdempotencyKey(conversationId, idempotencyKey);
    }

    @Override
    public BeginResult begin(Conversation conversation, Generation generation) {
        return delegate.begin(conversation, generation);
    }

    @Override
    public Optional<Generation> claim(UUID generationId, Instant startedAt) {
        return delegate.claim(generationId, startedAt);
    }

    @Override
    public Optional<Generation> claim(UUID generationId, Instant startedAt, Instant leaseUntil) {
        return delegate.claim(generationId, startedAt, leaseUntil);
    }

    @Override
    public boolean renewClaim(UUID generationId, UUID claimToken, Instant renewedAt, Instant leaseUntil) {
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(renewedAt, "renewedAt");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (!leaseUntil.isAfter(renewedAt)) {
            throw new IllegalArgumentException("leaseUntil must be after renewedAt");
        }
        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "pk", string("GEN#" + generationId),
                            "sk", string(META)))
                    .updateExpression("SET updatedAt = :updatedAt, leaseUntil = :leaseUntil")
                    .conditionExpression("#status = :running AND claimToken = :claimToken")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":updatedAt", number(renewedAt.toEpochMilli()),
                            ":leaseUntil", number(leaseUntil.toEpochMilli()),
                            ":running", string(GenerationStatus.RUNNING.name()),
                            ":claimToken", string(claimToken.toString())))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException exception) {
            return false;
        }
    }

    @Override
    public Optional<Generation> cancel(UUID generationId, Instant cancelledAt) {
        return delegate.cancel(generationId, cancelledAt);
    }

    @Override
    public void complete(Conversation conversation, Generation generation) {
        delegate.complete(conversation, generation);
    }

    @Override
    public void fail(Generation generation) {
        delegate.fail(generation);
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
