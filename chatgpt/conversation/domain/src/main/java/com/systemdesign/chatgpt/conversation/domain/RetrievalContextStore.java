package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RetrievalContextStore {
    List<Snippet> search(String userId, String query, int limit);

    void upsert(Snippet snippet);

    record Snippet(UUID id, String userId, String content, Instant updatedAt) {
        public Snippet {
            if (id == null || updatedAt == null) {
                throw new IllegalArgumentException("snippet id and updatedAt are required");
            }
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("snippet userId must not be blank");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("snippet content must not be blank");
            }
        }
    }
}
