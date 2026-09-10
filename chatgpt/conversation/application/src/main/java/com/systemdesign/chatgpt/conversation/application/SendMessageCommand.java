package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ModelCapability;

import java.util.Set;
import java.util.UUID;

public record SendMessageCommand(
        UUID conversationId,
        String idempotencyKey,
        String content,
        Set<ModelCapability> requiredCapabilities) {

    public SendMessageCommand(UUID conversationId, String idempotencyKey, String content) {
        this(conversationId, idempotencyKey, content, Set.of());
    }

    public SendMessageCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        requiredCapabilities = Set.copyOf(requiredCapabilities == null ? Set.of() : requiredCapabilities);
    }
}
