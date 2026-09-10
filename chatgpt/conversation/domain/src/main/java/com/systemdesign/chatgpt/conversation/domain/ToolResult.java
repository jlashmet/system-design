package com.systemdesign.chatgpt.conversation.domain;

import java.util.Objects;
import java.util.UUID;

public record ToolResult(UUID callId, Status status, String content) {
    public ToolResult {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(status, "status");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("tool result content must not be blank");
        }
    }

    public enum Status {
        SUCCESS,
        ERROR,
        DENIED,
        TIMED_OUT
    }
}
