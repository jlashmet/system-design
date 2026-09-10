package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
import com.systemdesign.chatgpt.conversation.domain.ReplayableGenerationEventBus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class InMemoryGenerationEventBus implements ReplayableGenerationEventBus {
    private final ConcurrentHashMap<UUID, StreamState> streams = new ConcurrentHashMap<>();

    @Override
    public void publish(UUID generationId, Event event) {
        streams.computeIfAbsent(generationId, ignored -> new StreamState()).publish(event);
    }

    @Override
    public Subscription subscribe(UUID generationId, Consumer<Event> consumer) {
        StreamState state = streams.computeIfAbsent(generationId, ignored -> new StreamState());
        Consumer<RecordedEvent> recordedConsumer = recorded -> consumer.accept(recorded.event());
        state.addLiveSubscriber(recordedConsumer);
        return () -> state.removeSubscriber(recordedConsumer);
    }

    @Override
    public Subscription subscribe(UUID generationId, long afterSequence, Consumer<RecordedEvent> consumer) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be >= 0");
        }
        StreamState state = streams.computeIfAbsent(generationId, ignored -> new StreamState());
        state.replayAndSubscribe(afterSequence, consumer);
        return () -> state.removeSubscriber(consumer);
    }

    private static final class StreamState {
        private long nextSequence = 1;
        private final List<RecordedEvent> history = new ArrayList<>();
        private final Set<Consumer<RecordedEvent>> subscribers = new HashSet<>();

        private synchronized void publish(Event event) {
            RecordedEvent recorded = new RecordedEvent(nextSequence++, event);
            history.add(recorded);
            subscribers.forEach(consumer -> consumer.accept(recorded));
        }

        private synchronized void addLiveSubscriber(Consumer<RecordedEvent> consumer) {
            subscribers.add(consumer);
        }

        private synchronized void replayAndSubscribe(long afterSequence, Consumer<RecordedEvent> consumer) {
            history.stream()
                    .filter(recorded -> recorded.sequence() > afterSequence)
                    .forEach(consumer);
            subscribers.add(consumer);
        }

        private synchronized void removeSubscriber(Consumer<RecordedEvent> consumer) {
            subscribers.remove(consumer);
        }
    }
}
