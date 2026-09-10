package com.systemdesign.chatgpt.conversation.domain;

import java.util.Optional;
import java.util.UUID;

public interface TurnRepository {
    Optional<Generation> findById(UUID generationId);

    Optional<Generation> findByIdempotencyKey(UUID conversationId, String idempotencyKey);

    BeginResult begin(Conversation conversation, Generation generation);

    void complete(Conversation conversation, Generation generation);

    void fail(Generation generation);

    record BeginResult(Generation generation, boolean created) {
    }
}
