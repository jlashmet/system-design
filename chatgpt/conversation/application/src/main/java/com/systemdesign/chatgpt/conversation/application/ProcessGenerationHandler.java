package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class ProcessGenerationHandler {
    private final ConversationRepository conversationRepository;
    private final TurnRepository turnRepository;
    private final ModelGateway modelGateway;
    private final GenerationEventBus eventBus;
    private final Supplier<UUID> idGenerator;
    private final Clock clock;

    public ProcessGenerationHandler(
            ConversationRepository conversationRepository,
            TurnRepository turnRepository,
            ModelGateway modelGateway,
            GenerationEventBus eventBus,
            Supplier<UUID> idGenerator,
            Clock clock) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository");
        this.turnRepository = Objects.requireNonNull(turnRepository, "turnRepository");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void handle(UUID generationId) {
        Instant startedAt = Instant.now(clock);
        Generation generation = turnRepository.claim(generationId, startedAt).orElse(null);
        if (generation == null) {
            if (turnRepository.findGenerationById(generationId).isEmpty()) {
                throw new NoSuchElementException("generation not found: " + generationId);
            }
            return;
        }

        Conversation conversation = conversationRepository.findById(generation.conversationId())
                .orElseThrow(() -> new NoSuchElementException("conversation not found: " + generation.conversationId()));

        try {
            ModelGateway.Completion completion = modelGateway.stream(
                    conversation.messages(),
                    delta -> publishDeltaUnlessCancelled(generation.id(), delta));
            if (isCancelled(generation.id())) {
                return;
            }

            Instant completedAt = Instant.now(clock);
            Message assistantMessage = new Message(
                    idGenerator.get(),
                    MessageRole.ASSISTANT,
                    completion.content(),
                    completedAt);
            conversation.append(assistantMessage);
            turnRepository.complete(conversation, generation.completed(assistantMessage.id(), completedAt));

            Generation finalState = turnRepository.findGenerationById(generation.id()).orElseThrow();
            if (finalState.status() == GenerationStatus.COMPLETED) {
                eventBus.publish(generation.id(), GenerationEventBus.Event.completed());
            }
        } catch (GenerationCancelledException ignored) {
            // Cancellation is a normal terminal outcome and is already published by the cancel use case.
        } catch (RuntimeException exception) {
            turnRepository.fail(generation.failed(Instant.now(clock)));
            if (!isCancelled(generation.id())) {
                eventBus.publish(generation.id(), GenerationEventBus.Event.failed(exception.getMessage()));
            }
            throw exception;
        }
    }

    private void publishDeltaUnlessCancelled(UUID generationId, String delta) {
        if (isCancelled(generationId)) {
            throw new GenerationCancelledException();
        }
        eventBus.publish(generationId, GenerationEventBus.Event.delta(delta));
    }

    private boolean isCancelled(UUID generationId) {
        return turnRepository.findGenerationById(generationId)
                .map(Generation::status)
                .filter(status -> status == GenerationStatus.CANCELLED)
                .isPresent();
    }

    private static final class GenerationCancelledException extends RuntimeException {
    }
}
