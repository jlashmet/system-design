package com.systemdesign.chatgpt.conversation.domain;

public interface ToolHandler {
    ToolDefinition definition();

    ToolResult execute(ToolCall call);
}
