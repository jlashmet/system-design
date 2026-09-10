package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Generation(
        UUID id,
        UUID conversationId,
        String idempotencyKey,
        String requestContent,
        UUID userMessageId,
        UUID assistantMessageId,
        GenerationStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public Generation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
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
        return new Generation(
                id,
                conversationId,
                idempotencyKey,
                requestContent,
                userMessageId,
                null,
                GenerationStatus.PENDING,
                createdAt,
                createdAt);
    }

    public Generation running(Instant startedAt) {
        return new Generation(
                id,
                conversationId,
                idempotencyKey,
                requestContent,
                userMessageId,
                assistantMessageId,
                GenerationStatus.RUNNING,
                createdAt,
                startedAt);
    }

    public Generation completed(UUID assistantMessageId, Instant completedAt) {
        return new Generation(
                id,
                conversationId,
                idempotencyKey,
                requestContent,
                userMessageId,
                Objects.requireNonNull(assistantMessageId, "assistantMessageId"),
                GenerationStatus.COMPLETED,
                createdAt,
                completedAt);
    }

    public Generation failed(Instant failedAt) {
        return new Generation(
                id,
                conversationId,
                idempotencyKey,
                requestContent,
                userMessageId,
                assistantMessageId,
                GenerationStatus.FAILED,
                createdAt,
                failedAt);
    }

    public Generation cancelled(Instant cancelledAt) {
        return new Generation(
                id,
                conversationId,
                idempotencyKey,
                requestContent,
                userMessageId,
                assistantMessageId,
                GenerationStatus.CANCELLED,
                createdAt,
                cancelledAt);
    }
}
