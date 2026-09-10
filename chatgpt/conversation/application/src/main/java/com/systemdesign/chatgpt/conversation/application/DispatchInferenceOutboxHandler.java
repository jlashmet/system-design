package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import com.systemdesign.chatgpt.conversation.domain.InferenceOutbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class DispatchInferenceOutboxHandler {
    public enum Result { EMPTY, DISPATCHED, RETRY_LATER }

    private final InferenceOutbox outbox;
    private final InferenceJobQueue queue;
    private final Duration claimLease;
    private final Clock clock;

    public DispatchInferenceOutboxHandler(InferenceOutbox outbox, InferenceJobQueue queue, Duration claimLease, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.claimLease = Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative()) throw new IllegalArgumentException("claimLease must be > 0");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result dispatchOne() {
        Instant now = Instant.now(clock);
        InferenceOutbox.Entry entry = outbox.claimNext(now, now.plus(claimLease)).orElse(null);
        if (entry == null) return Result.EMPTY;

        boolean queued = queue.tryEnqueue(InferenceJobQueue.Job.firstAttempt(entry.generationId()));
        if (!queued) {
            outbox.release(entry.generationId(), entry.claimToken());
            return Result.RETRY_LATER;
        }

        // If this write loses its fence after the queue accepted the message, the outbox will eventually
        // be reclaimed and may enqueue a duplicate. Generation claim fencing makes that at-least-once path safe.
        outbox.markDispatched(entry.generationId(), entry.claimToken(), Instant.now(clock));
        return Result.DISPATCHED;
    }
}
