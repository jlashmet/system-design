package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ToolExecutionContext(
        UUID generationId,
        UUID callId,
        String idempotencyKey,
        Instant deadline) {
    public ToolExecutionContext {
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(deadline, "deadline");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
