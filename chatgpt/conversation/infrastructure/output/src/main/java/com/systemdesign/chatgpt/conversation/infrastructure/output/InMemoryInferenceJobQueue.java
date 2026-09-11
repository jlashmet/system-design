package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;

public final class InMemoryInferenceJobQueue implements InferenceJobQueue {
    private final int capacity;
    private final Clock clock;
    private final PriorityQueue<ScheduledJob> jobs = new PriorityQueue<>(
            Comparator.comparing(ScheduledJob::availableAt).thenComparingLong(ScheduledJob::sequence));
    private final List<DeadLetter> deadLetters = new ArrayList<>();
    private long sequence;

    public InMemoryInferenceJobQueue(int capacity) {
        this(capacity, Clock.systemUTC());
    }

    public InMemoryInferenceJobQueue(int capacity, Clock clock) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean tryEnqueue(Job job) {
        return tryEnqueue(job, Duration.ZERO);
    }

    @Override
    public synchronized boolean tryEnqueue(Job job, Duration delay) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) throw new IllegalArgumentException("delay must not be negative");
        if (jobs.size() >= capacity) return false;
        jobs.add(new ScheduledJob(job, Instant.now(clock).plus(delay), sequence++));
        return true;
    }

    @Override
    public synchronized Optional<Delivery> poll() {
        ScheduledJob scheduled = jobs.peek();
        if (scheduled == null || scheduled.availableAt().isAfter(Instant.now(clock))) return Optional.empty();
        jobs.remove();
        return Optional.of(new Delivery(scheduled.job(), UUID.randomUUID().toString()));
    }

    @Override
    public void acknowledge(Delivery delivery) {
        // Poll removes in-memory jobs immediately. Durable adapters use the receipt here.
    }

    @Override
    public synchronized void deadLetter(Job job, String reason) {
        deadLetters.add(new DeadLetter(job, reason == null ? "" : reason));
    }

    public synchronized List<DeadLetter> deadLetters() {
        return List.copyOf(deadLetters);
    }

    private record ScheduledJob(Job job, Instant availableAt, long sequence) { }

    public record DeadLetter(Job job, String reason) { }
}
