package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
import com.systemdesign.chatgpt.conversation.domain.GenerationEventStore;
import com.systemdesign.chatgpt.conversation.domain.ReplayableGenerationEventBus;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class StoredGenerationEventBus implements ReplayableGenerationEventBus {
    private final GenerationEventStore store;
    private final Duration pollInterval;
    private final int batchSize;

    public StoredGenerationEventBus(GenerationEventStore store, Duration pollInterval, int batchSize) {
        this.store = Objects.requireNonNull(store, "store");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be > 0");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        this.batchSize = batchSize;
    }

    @Override
    public void publish(UUID generationId, Event event) {
        store.append(generationId, event);
    }

    @Override
    public Subscription subscribe(UUID generationId, Consumer<Event> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        long afterSequence = store.latestSequence(generationId);
        return subscribe(generationId, afterSequence, recorded -> consumer.accept(recorded.event()));
    }

    @Override
    public Subscription subscribe(UUID generationId, long afterSequence, Consumer<RecordedEvent> consumer) {
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(consumer, "consumer");
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be >= 0");
        }

        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicLong cursor = new AtomicLong(afterSequence);
        if (drainAvailable(generationId, cursor, consumer, closed)) {
            return () -> closed.set(true);
        }

        Thread poller = Thread.ofVirtual().name("generation-events-" + generationId).start(() -> {
            while (!closed.get()) {
                try {
                    if (drainAvailable(generationId, cursor, consumer, closed)) {
                        break;
                    }
                    Thread.sleep(pollInterval);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    closed.set(true);
                } catch (RuntimeException exception) {
                    try {
                        Thread.sleep(pollInterval);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        closed.set(true);
                    }
                }
            }
        });

        return () -> {
            if (closed.compareAndSet(false, true)) {
                poller.interrupt();
            }
        };
    }

    private boolean drainAvailable(
            UUID generationId,
            AtomicLong cursor,
            Consumer<RecordedEvent> consumer,
            AtomicBoolean closed) {
        while (!closed.get()) {
            List<RecordedEvent> batch = store.listAfter(generationId, cursor.get(), batchSize);
            if (batch.isEmpty()) {
                return false;
            }
            for (RecordedEvent recorded : batch) {
                if (closed.get()) {
                    return true;
                }
                consumer.accept(recorded);
                cursor.set(recorded.sequence());
                if (isTerminal(recorded.event().type())) {
                    closed.set(true);
                    return true;
                }
            }
            if (batch.size() < batchSize) {
                return false;
            }
        }
        return true;
    }

    private boolean isTerminal(GenerationEventBus.Type type) {
        return type == GenerationEventBus.Type.COMPLETED
                || type == GenerationEventBus.Type.FAILED
                || type == GenerationEventBus.Type.CANCELLED;
    }
}
