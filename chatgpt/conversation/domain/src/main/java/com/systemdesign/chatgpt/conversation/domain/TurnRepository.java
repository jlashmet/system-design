package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TurnRepository {
    Optional<Generation> findGenerationById(UUID generationId);

    Optional<Generation> findByIdempotencyKey(UUID conversationId, String idempotencyKey);

    BeginResult begin(Conversation conversation, Generation generation);

    Optional<Generation> claim(UUID generationId, Instant startedAt);

    default Optional<Generation> claim(UUID generationId, Instant startedAt, Instant leaseUntil) {
        return claim(generationId, startedAt);
    }

    Optional<Generation> cancel(UUID generationId, Instant cancelledAt);

    void complete(Conversation conversation, Generation generation);

    void fail(Generation generation);

    record BeginResult(Generation generation, boolean created) {
    }
}
