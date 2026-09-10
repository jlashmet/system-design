package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class GetGenerationHandler {
    private final TurnRepository turnRepository;

    public GetGenerationHandler(TurnRepository turnRepository) {
        this.turnRepository = Objects.requireNonNull(turnRepository, "turnRepository");
    }

    public Generation handle(UUID conversationId, UUID generationId) {
        Generation generation = turnRepository.findById(generationId)
                .orElseThrow(() -> new NoSuchElementException("generation not found: " + generationId));
        if (!generation.conversationId().equals(conversationId)) {
            throw new NoSuchElementException("generation not found: " + generationId);
        }
        return generation;
    }
}
