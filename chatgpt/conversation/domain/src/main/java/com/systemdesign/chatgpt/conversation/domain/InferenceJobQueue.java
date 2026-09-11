package com.systemdesign.chatgpt.conversation.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface InferenceJobQueue {
    boolean tryEnqueue(Job job);

    default boolean tryEnqueue(Job job, Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) throw new IllegalArgumentException("delay must not be negative");
        if (!delay.isZero()) throw new UnsupportedOperationException("delayed enqueue is not supported");
        return tryEnqueue(job);
    }

    Optional<Delivery> poll();

    void acknowledge(Delivery delivery);

    default void renew(Delivery delivery) {
    }

    void deadLetter(Job job, String reason);

    record Job(UUID generationId, int attempt) {
        public Job {
            Objects.requireNonNull(generationId, "generationId");
            if (attempt < 1) throw new IllegalArgumentException("attempt must be >= 1");
        }

        public static Job firstAttempt(UUID generationId) { return new Job(generationId, 1); }
        public Job nextAttempt() { return new Job(generationId, attempt + 1); }
    }

    record Delivery(Job job, String receipt) {
        public Delivery {
            Objects.requireNonNull(job, "job");
            if (receipt == null || receipt.isBlank()) throw new IllegalArgumentException("receipt must not be blank");
        }
    }
}
