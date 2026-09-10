package com.systemdesign.chatgpt.conversation.application;

import java.util.UUID;

public record SendMessageCommand(UUID conversationId, String idempotencyKey, String content) {
    public SendMessageCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
