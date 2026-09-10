package com.systemdesign.chatgpt.conversation.api;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(UUID id, String role, String content, Instant createdAt) {
}
