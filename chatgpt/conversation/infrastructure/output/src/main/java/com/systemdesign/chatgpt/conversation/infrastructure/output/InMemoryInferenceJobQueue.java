package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class InMemoryInferenceJobQueue implements InferenceJobQueue {
    private final ConcurrentLinkedQueue<UUID> jobs = new ConcurrentLinkedQueue<>();

    @Override
    public void enqueue(UUID generationId) {
        jobs.offer(generationId);
    }

    @Override
    public Optional<UUID> poll() {
        return Optional.ofNullable(jobs.poll());
    }
}
