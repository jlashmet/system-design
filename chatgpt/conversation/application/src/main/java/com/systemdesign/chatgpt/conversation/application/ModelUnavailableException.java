package com.systemdesign.chatgpt.conversation.application;

public final class ModelUnavailableException extends RuntimeException {
    private final boolean retryable;

    public ModelUnavailableException(String message) {
        this(message, true, null);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        this(message, true, cause);
    }

    public ModelUnavailableException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
