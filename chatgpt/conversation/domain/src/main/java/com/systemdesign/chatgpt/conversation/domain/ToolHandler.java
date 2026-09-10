package com.systemdesign.chatgpt.conversation.domain;

public interface ToolHandler {
    ToolDefinition definition();

    ToolResult execute(ToolCall call);

    default ToolResult execute(ToolCall call, ToolExecutionContext context) {
        return execute(call);
    }
}
