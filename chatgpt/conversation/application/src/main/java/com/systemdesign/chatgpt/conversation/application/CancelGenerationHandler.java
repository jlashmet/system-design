package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class CancelGenerationHandler {
    private final TurnRepository turnRepository;
    private final GenerationEventBus eventBus;
    private final Clock clock;

    public CancelGenerationHandler(TurnRepository turnRepository, GenerationEventBus eventBus, Clock clock) {
        this.turnRepository = Objects.requireNonNull(turnRepository, "turnRepository");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Generation handle(UUID conversationId, UUID generationId) {
        Generation existing = turnRepository.findGenerationById(generationId)
                .orElseThrow(() -> new NoSuchElementException("generation not found: " + generationId));
        if (!existing.conversationId().equals(conversationId)) {
            throw new NoSuchElementException("generation not found: " + generationId);
        }

        Instant cancelledAt = Instant.now(clock);
        Generation result = turnRepository.cancel(generationId, cancelledAt)
                .orElseThrow(() -> new NoSuchElementException("generation not found: " + generationId));
        if (existing.status() != GenerationStatus.CANCELLED && result.status() == GenerationStatus.CANCELLED) {
            eventBus.publish(generationId, GenerationEventBus.Event.cancelled());
        }
        return result;
    }
}
