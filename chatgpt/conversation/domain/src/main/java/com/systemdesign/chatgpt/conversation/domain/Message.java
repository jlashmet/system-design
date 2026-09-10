package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Message(UUID id, MessageRole role, String content, Instant createdAt) {
    public Message {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("message content must not be blank");
        }
    }
}
