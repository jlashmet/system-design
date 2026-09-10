package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Generation;
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
    private final Supplier<UUID> idGenerator;
    private final Clock clock;

    public ProcessGenerationHandler(
            ConversationRepository conversationRepository,
            TurnRepository turnRepository,
            ModelGateway modelGateway,
            Supplier<UUID> idGenerator,
            Clock clock) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository");
        this.turnRepository = Objects.requireNonNull(turnRepository, "turnRepository");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void handle(UUID generationId) {
        Instant startedAt = Instant.now(clock);
        Generation generation = turnRepository.claim(generationId, startedAt).orElse(null);
        if (generation == null) {
            if (turnRepository.findById(generationId).isEmpty()) {
                throw new NoSuchElementException("generation not found: " + generationId);
            }
            return;
        }

        Conversation conversation = conversationRepository.findById(generation.conversationId())
                .orElseThrow(() -> new NoSuchElementException("conversation not found: " + generation.conversationId()));

        try {
            ModelGateway.Completion completion = modelGateway.complete(conversation.messages());
            Instant completedAt = Instant.now(clock);
            Message assistantMessage = new Message(
                    idGenerator.get(),
                    MessageRole.ASSISTANT,
                    completion.content(),
                    completedAt);
            conversation.append(assistantMessage);
            turnRepository.complete(conversation, generation.completed(assistantMessage.id(), completedAt));
        } catch (RuntimeException exception) {
            turnRepository.fail(generation.failed(Instant.now(clock)));
            throw exception;
        }
    }
}
