package com.systemdesign.chatgpt.conversation.application;

public final class ToolInvocationInProgressException extends RuntimeException {
    public ToolInvocationInProgressException(String toolName) {
        super("tool invocation is already in progress: " + toolName);
    }
}
