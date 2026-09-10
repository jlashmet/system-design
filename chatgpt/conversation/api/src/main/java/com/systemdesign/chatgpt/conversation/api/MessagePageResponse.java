package com.systemdesign.chatgpt.conversation.api;

import java.util.List;

public record MessagePageResponse(List<MessageResponse> messages, String nextCursor) {
    public MessagePageResponse {
        messages = List.copyOf(messages);
    }
}
