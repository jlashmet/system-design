package com.systemdesign.chatgpt.conversation.application;

public final class InferenceQueueSaturatedException extends RuntimeException {
    public InferenceQueueSaturatedException() {
        super("inference queue is saturated; retry the same idempotent request later");
    }
}
