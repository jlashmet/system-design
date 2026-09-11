package com.systemdesign.chatgpt.conversation.domain;

public final class ModelProviderException extends RuntimeException {
    private final boolean retryable;
    private final Integer statusCode;

    public ModelProviderException(String message, boolean retryable) {
        this(message, retryable, null, null);
    }

    public ModelProviderException(String message, boolean retryable, Integer statusCode) {
        this(message, retryable, statusCode, null);
    }

    public ModelProviderException(String message, boolean retryable, Throwable cause) {
        this(message, retryable, null, cause);
    }

    private ModelProviderException(String message, boolean retryable, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.statusCode = statusCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public Integer statusCode() {
        return statusCode;
    }
}
