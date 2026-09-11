package com.systemdesign.chatgpt.conversation.domain;

import java.time.Duration;

public final class ModelProviderException extends RuntimeException {
    private final boolean retryable;
    private final Integer statusCode;
    private final Duration retryAfter;

    public ModelProviderException(String message, boolean retryable) {
        this(message, retryable, null, null, null);
    }

    public ModelProviderException(String message, boolean retryable, Integer statusCode) {
        this(message, retryable, statusCode, null, null);
    }

    public ModelProviderException(String message, boolean retryable, Integer statusCode, Duration retryAfter) {
        this(message, retryable, statusCode, retryAfter, null);
    }

    public ModelProviderException(String message, boolean retryable, Throwable cause) {
        this(message, retryable, null, null, cause);
    }

    private ModelProviderException(String message, boolean retryable, Integer statusCode,
            Duration retryAfter, Throwable cause) {
        super(message, cause);
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        this.retryable = retryable;
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    public boolean retryable() {
        return retryable;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
