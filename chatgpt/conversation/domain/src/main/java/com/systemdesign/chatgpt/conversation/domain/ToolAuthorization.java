package com.systemdesign.chatgpt.conversation.domain;

import java.util.UUID;

public interface ToolAuthorization {
    boolean isAllowed(String userId, UUID conversationId, String toolName);
}
