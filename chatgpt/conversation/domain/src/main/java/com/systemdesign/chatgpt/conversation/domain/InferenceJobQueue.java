package com.systemdesign.chatgpt.conversation.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface InferenceJobQueue {
    boolean tryEnqueue(Job job);

    Optional<Job> poll();

    void deadLetter(Job job, String reason);

    record Job(UUID generationId, int attempt) {
        public Job {
            Objects.requireNonNull(generationId, "generationId");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be >= 1");
            }
        }

        public static Job firstAttempt(UUID generationId) {
            return new Job(generationId, 1);
        }

        public Job nextAttempt() {
            return new Job(generationId, attempt + 1);
        }
    }
}
