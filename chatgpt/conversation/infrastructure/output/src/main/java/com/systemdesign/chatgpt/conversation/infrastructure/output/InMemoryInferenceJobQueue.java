package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;

public final class InMemoryInferenceJobQueue implements InferenceJobQueue {
    private final ArrayBlockingQueue<Job> jobs;
    private final List<DeadLetter> deadLetters = new ArrayList<>();

    public InMemoryInferenceJobQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.jobs = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public boolean tryEnqueue(Job job) {
        return jobs.offer(job);
    }

    @Override
    public Optional<Job> poll() {
        return Optional.ofNullable(jobs.poll());
    }

    @Override
    public synchronized void deadLetter(Job job, String reason) {
        deadLetters.add(new DeadLetter(job, reason == null ? "" : reason));
    }

    public synchronized List<DeadLetter> deadLetters() {
        return List.copyOf(deadLetters);
    }

    public record DeadLetter(Job job, String reason) {
    }
}
