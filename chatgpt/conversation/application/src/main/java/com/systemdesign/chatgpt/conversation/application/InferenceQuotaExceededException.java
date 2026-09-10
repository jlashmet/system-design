package com.systemdesign.chatgpt.conversation.application;

public final class InferenceQuotaExceededException extends RuntimeException {
    public InferenceQuotaExceededException() {
        super("inference quota exceeded");
    }
}
