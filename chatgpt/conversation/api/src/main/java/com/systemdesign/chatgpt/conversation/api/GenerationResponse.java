package com.systemdesign.chatgpt.conversation.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GenerationResponse(
        UUID id,
        String status,
        List<String> requiredCapabilities,
        UUID userMessageId,
        UUID assistantMessageId,
        Instant createdAt,
        Instant updatedAt) {
    public GenerationResponse {
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
    }
}
