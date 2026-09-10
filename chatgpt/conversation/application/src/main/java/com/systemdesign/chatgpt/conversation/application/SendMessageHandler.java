package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Generation;
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

public final class SendMessageHandler {
    private final ConversationRepository repository;
    private final TurnRepository turnRepository;
    private final ModelGateway modelGateway;
    private final Supplier<UUID> idGenerator;
    private final Clock clock;

    public SendMessageHandler(
            ConversationRepository repository,
            TurnRepository turnRepository,
            ModelGateway modelGateway,
            Supplier<UUID> idGenerator,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.turnRepository = Objects.requireNonNull(turnRepository, "turnRepository");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SendMessageResult handle(SendMessageCommand command) {
        Objects.requireNonNull(command, "command");
        Conversation conversation = loadConversation(command.conversationId());

        Generation generation = turnRepository
                .findByIdempotencyKey(command.conversationId(), command.idempotencyKey())
                .orElse(null);

        if (generation != null) {
            validateReplay(generation, command.content());
            if (generation.status() == GenerationStatus.COMPLETED) {
                return completedResult(conversation, generation);
            }
            return generate(conversation, generation);
        }

        Instant createdAt = Instant.now(clock);
        Message userMessage = new Message(idGenerator.get(), MessageRole.USER, command.content(), createdAt);
        conversation.append(userMessage);
        Generation candidate = Generation.pending(
                idGenerator.get(),
                conversation.id(),
                command.idempotencyKey(),
                command.content(),
                userMessage.id(),
                createdAt);

        TurnRepository.BeginResult begin = turnRepository.begin(conversation, candidate);
        if (!begin.created()) {
            Generation existing = begin.generation();
            validateReplay(existing, command.content());
            Conversation persisted = loadConversation(command.conversationId());
            if (existing.status() == GenerationStatus.COMPLETED) {
                return completedResult(persisted, existing);
            }
            return generate(persisted, existing);
        }

        return generate(conversation, candidate);
    }

    private SendMessageResult generate(Conversation conversation, Generation generation) {
        Message userMessage = findMessage(conversation, generation.userMessageId());
        try {
            ModelGateway.Completion completion = modelGateway.complete(conversation.messages());
            Instant completedAt = Instant.now(clock);
            Message assistantMessage = new Message(
                    idGenerator.get(),
                    MessageRole.ASSISTANT,
                    completion.content(),
                    completedAt);
            conversation.append(assistantMessage);
            Generation completed = generation.completed(assistantMessage.id(), completedAt);
            turnRepository.complete(conversation, completed);
            return new SendMessageResult(conversation, userMessage, assistantMessage);
        } catch (RuntimeException exception) {
            turnRepository.fail(generation.failed(Instant.now(clock)));
            throw exception;
        }
    }

    private SendMessageResult completedResult(Conversation conversation, Generation generation) {
        Message userMessage = findMessage(conversation, generation.userMessageId());
        Message assistantMessage = findMessage(conversation, generation.assistantMessageId());
        return new SendMessageResult(conversation, userMessage, assistantMessage);
    }

    private void validateReplay(Generation generation, String content) {
        if (!generation.requestContent().equals(content)) {
            throw new TurnConflictException("idempotency key was already used with different content");
        }
    }

    private Conversation loadConversation(UUID conversationId) {
        return repository.findById(conversationId)
                .orElseThrow(() -> new NoSuchElementException("conversation not found: " + conversationId));
    }

    private Message findMessage(Conversation conversation, UUID messageId) {
        return conversation.messages().stream()
                .filter(message -> message.id().equals(messageId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("generation references missing message: " + messageId));
    }
}
