package com.systemdesign.chatgpt.conversation.application;

import java.time.Duration;

public final class ModelUnavailableException extends RuntimeException {
    private final boolean retryable;
    private final Duration retryAfter;

    public ModelUnavailableException(String message) {
        this(message, true, null, null);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        this(message, true, null, cause);
    }

    public ModelUnavailableException(String message, boolean retryable, Throwable cause) {
        this(message, retryable, null, cause);
    }

    public ModelUnavailableException(String message, boolean retryable, Duration retryAfter, Throwable cause) {
        super(message, cause);
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public boolean retryable() {
        return retryable;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
