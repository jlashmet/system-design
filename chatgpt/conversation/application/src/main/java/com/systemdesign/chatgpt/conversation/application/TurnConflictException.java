package com.systemdesign.chatgpt.conversation.application;

public final class TurnConflictException extends RuntimeException {
    public TurnConflictException(String message) {
        super(message);
    }
}
