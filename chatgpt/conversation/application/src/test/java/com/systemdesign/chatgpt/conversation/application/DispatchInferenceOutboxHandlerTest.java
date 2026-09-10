package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import com.systemdesign.chatgpt.conversation.domain.InferenceOutbox;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchInferenceOutboxHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-10T23:00:00Z");

    @Test
    void marksOutboxDispatchedAfterQueueAccepts() {
        FakeOutbox outbox = new FakeOutbox(UUID.randomUUID());
        FakeQueue queue = new FakeQueue(true);
        DispatchInferenceOutboxHandler handler = new DispatchInferenceOutboxHandler(
                outbox, queue, Duration.ofSeconds(30), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(handler.dispatchOne()).isEqualTo(DispatchInferenceOutboxHandler.Result.DISPATCHED);
        assertThat(queue.jobs).containsExactly(InferenceJobQueue.Job.firstAttempt(outbox.generationId));
        assertThat(outbox.dispatched).isTrue();
    }

    @Test
    void releasesOutboxWhenQueueIsUnavailable() {
        FakeOutbox outbox = new FakeOutbox(UUID.randomUUID());
        FakeQueue queue = new FakeQueue(false);
        DispatchInferenceOutboxHandler handler = new DispatchInferenceOutboxHandler(
                outbox, queue, Duration.ofSeconds(30), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(handler.dispatchOne()).isEqualTo(DispatchInferenceOutboxHandler.Result.RETRY_LATER);
        assertThat(outbox.released).isTrue();
        assertThat(outbox.dispatched).isFalse();
    }

    private static final class FakeOutbox implements InferenceOutbox {
        private final UUID generationId;
        private final UUID token = UUID.randomUUID();
        private boolean claimed;
        private boolean dispatched;
        private boolean released;

        private FakeOutbox(UUID generationId) { this.generationId = generationId; }

        @Override public Optional<Entry> claimNext(Instant claimedAt, Instant leaseUntil) {
            if (claimed) return Optional.empty();
            claimed = true;
            return Optional.of(new Entry(generationId, token, NOW.minusSeconds(1), leaseUntil));
        }
        @Override public boolean markDispatched(UUID generationId, UUID claimToken, Instant dispatchedAt) {
            if (!this.generationId.equals(generationId) || !token.equals(claimToken)) return false;
            dispatched = true; return true;
        }
        @Override public boolean release(UUID generationId, UUID claimToken) {
            if (!this.generationId.equals(generationId) || !token.equals(claimToken)) return false;
            released = true; return true;
        }
    }

    private static final class FakeQueue implements InferenceJobQueue {
        private final boolean accepting;
        private final Queue<Job> jobs = new ArrayDeque<>();
        private FakeQueue(boolean accepting) { this.accepting = accepting; }
        @Override public boolean tryEnqueue(Job job) { if (!accepting) return false; jobs.add(job); return true; }
        @Override public Optional<Delivery> poll() { return Optional.empty(); }
        @Override public void acknowledge(Delivery delivery) { }
        @Override public void deadLetter(Job job, String reason) { }
    }
}
