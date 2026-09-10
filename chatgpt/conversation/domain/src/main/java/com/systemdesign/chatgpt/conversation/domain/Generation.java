package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record Generation(
        UUID id,
        UUID conversationId,
        String idempotencyKey,
        String requestContent,
        Set<ModelCapability> requiredCapabilities,
        UUID userMessageId,
        UUID assistantMessageId,
        GenerationStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public Generation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
        requiredCapabilities = Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        Objects.requireNonNull(userMessageId, "userMessageId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (requestContent == null || requestContent.isBlank()) {
            throw new IllegalArgumentException("requestContent must not be blank");
        }
        if (status == GenerationStatus.COMPLETED && assistantMessageId == null) {
            throw new IllegalArgumentException("completed generation requires assistantMessageId");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot predate createdAt");
        }
    }

    public static Generation pending(
            UUID id,
            UUID conversationId,
            String idempotencyKey,
            String requestContent,
            UUID userMessageId,
            Instant createdAt) {
        return pending(id, conversationId, idempotencyKey, requestContent, Set.of(), userMessageId, createdAt);
    }

    public static Generation pending(
            UUID id,
            UUID conversationId,
            String idempotencyKey,
            String requestContent,
            Set<ModelCapability> requiredCapabilities,
            UUID userMessageId,
            Instant createdAt) {
        return new Generation(
                id,
                conversationId,
                idempotencyKey,
                requestContent,
                requiredCapabilities,
                userMessageId,
                null,
                GenerationStatus.PENDING,
                createdAt,
                createdAt);
    }

    public Generation running(Instant startedAt) {
        return withStatus(assistantMessageId, GenerationStatus.RUNNING, startedAt);
    }

    public Generation completed(UUID assistantMessageId, Instant completedAt) {
        return withStatus(Objects.requireNonNull(assistantMessageId, "assistantMessageId"), GenerationStatus.COMPLETED, completedAt);
    }

    public Generation failed(Instant failedAt) {
        return withStatus(assistantMessageId, GenerationStatus.FAILED, failedAt);
    }

    public Generation cancelled(Instant cancelledAt) {
        return withStatus(assistantMessageId, GenerationStatus.CANCELLED, cancelledAt);
    }

    private Generation withStatus(UUID assistantMessageId, GenerationStatus status, Instant updatedAt) {
        return new Generation(
                id,
                conversationId,
                idempotencyKey,
                requestContent,
                requiredCapabilities,
                userMessageId,
                assistantMessageId,
                status,
                createdAt,
                updatedAt);
    }
}
