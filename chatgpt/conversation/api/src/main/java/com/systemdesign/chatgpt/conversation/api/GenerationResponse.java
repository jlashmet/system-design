package com.systemdesign.chatgpt.conversation.api;

import java.time.Instant;
import java.util.UUID;

public record GenerationResponse(
        UUID id,
        String status,
        UUID userMessageId,
        UUID assistantMessageId,
        Instant createdAt,
        Instant updatedAt) {
}
