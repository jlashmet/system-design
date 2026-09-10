package com.systemdesign.chatgpt.conversation.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationResponse(
        UUID conversationId,
        String userId,
        Instant createdAt,
        List<MessageResponse> messages) {
}
