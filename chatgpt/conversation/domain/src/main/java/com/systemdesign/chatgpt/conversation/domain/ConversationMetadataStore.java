package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ConversationMetadataStore {
    Optional<Metadata> find(UUID conversationId);

    record Metadata(UUID conversationId, String userId, Instant createdAt) {
        public Metadata {
            if (conversationId == null || createdAt == null) {
                throw new IllegalArgumentException("conversationId and createdAt are required");
            }
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId must not be blank");
            }
        }
    }
}
