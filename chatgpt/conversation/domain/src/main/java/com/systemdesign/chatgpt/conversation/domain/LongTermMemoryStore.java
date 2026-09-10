package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LongTermMemoryStore {
    List<Memory> list(String userId, int limit);

    void upsert(Memory memory);

    record Memory(UUID id, String userId, String content, Instant updatedAt) {
        public Memory {
            if (id == null || updatedAt == null) {
                throw new IllegalArgumentException("memory id and updatedAt are required");
            }
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("memory userId must not be blank");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("memory content must not be blank");
            }
        }
    }
}
