package com.systemdesign.chatgpt.conversation.api;

import java.util.List;

public record SendMessageRequest(String content, List<String> requiredCapabilities) {
    public SendMessageRequest {
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
    }
}
