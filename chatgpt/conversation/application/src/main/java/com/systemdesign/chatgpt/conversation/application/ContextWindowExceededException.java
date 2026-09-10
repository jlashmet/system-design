package com.systemdesign.chatgpt.conversation.application;

public final class ContextWindowExceededException extends RuntimeException {
    public ContextWindowExceededException(String message) {
        super(message);
    }
}
