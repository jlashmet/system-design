package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryInferenceJobQueueTest {
    @Test
    void delayedJobIsNotVisibleUntilItsAvailabilityTime() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-11T20:00:00Z"));
        InMemoryInferenceJobQueue queue = new InMemoryInferenceJobQueue(2, clock);
        InferenceJobQueue.Job delayed = InferenceJobQueue.Job.firstAttempt(UUID.randomUUID());

        assertThat(queue.tryEnqueue(delayed, Duration.ofSeconds(5))).isTrue();
        assertThat(queue.poll()).isEmpty();
        clock.advance(Duration.ofSeconds(4));
        assertThat(queue.poll()).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        assertThat(queue.poll().orElseThrow().job()).isEqualTo(delayed);
    }

    @Test
    void availableJobIsNotBlockedByLaterDelayedJob() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-11T20:00:00Z"));
        InMemoryInferenceJobQueue queue = new InMemoryInferenceJobQueue(2, clock);
        InferenceJobQueue.Job later = InferenceJobQueue.Job.firstAttempt(UUID.randomUUID());
        InferenceJobQueue.Job immediate = InferenceJobQueue.Job.firstAttempt(UUID.randomUUID());

        queue.tryEnqueue(later, Duration.ofMinutes(1));
        queue.tryEnqueue(immediate);

        assertThat(queue.poll().orElseThrow().job()).isEqualTo(immediate);
        assertThat(queue.poll()).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
