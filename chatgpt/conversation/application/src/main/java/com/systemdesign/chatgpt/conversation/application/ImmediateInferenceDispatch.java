package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.InferenceDispatch;
import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;

import java.util.Objects;

public final class ImmediateInferenceDispatch implements InferenceDispatch {
    private final InferenceJobQueue queue;

    public ImmediateInferenceDispatch(InferenceJobQueue queue) {
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    @Override
    public void dispatch(Generation generation) {
        Objects.requireNonNull(generation, "generation");
        if (!queue.tryEnqueue(InferenceJobQueue.Job.firstAttempt(generation.id()))) {
            throw new InferenceQueueSaturatedException();
        }
    }
}
