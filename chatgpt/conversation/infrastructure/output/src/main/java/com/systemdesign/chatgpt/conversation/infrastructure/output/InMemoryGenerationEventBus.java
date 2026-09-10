package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class InMemoryGenerationEventBus implements GenerationEventBus {
    private final ConcurrentHashMap<UUID, Set<Consumer<Event>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void publish(UUID generationId, Event event) {
        Set<Consumer<Event>> consumers = subscribers.get(generationId);
        if (consumers == null) {
            return;
        }
        consumers.forEach(consumer -> consumer.accept(event));
    }

    @Override
    public Subscription subscribe(UUID generationId, Consumer<Event> consumer) {
        subscribers.computeIfAbsent(generationId, ignored -> ConcurrentHashMap.newKeySet()).add(consumer);
        return () -> {
            Set<Consumer<Event>> consumers = subscribers.get(generationId);
            if (consumers == null) {
                return;
            }
            consumers.remove(consumer);
            if (consumers.isEmpty()) {
                subscribers.remove(generationId, consumers);
            }
        };
    }
}
