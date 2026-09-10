package com.systemdesign.chatgpt.conversation.api;

import java.util.List;

public record ConversationPageResponse(List<ConversationResponse> conversations, String nextCursor) {
    public ConversationPageResponse {
        conversations = List.copyOf(conversations);
    }
}
