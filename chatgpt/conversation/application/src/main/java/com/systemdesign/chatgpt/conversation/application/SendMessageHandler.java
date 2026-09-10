package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
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
    private final InferenceJobQueue inferenceJobQueue;
    private final Supplier<UUID> idGenerator;
    private final Clock clock;

    public SendMessageHandler(
            ConversationRepository repository,
            TurnRepository turnRepository,
            InferenceJobQueue inferenceJobQueue,
            Supplier<UUID> idGenerator,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.turnRepository = Objects.requireNonNull(turnRepository, "turnRepository");
        this.inferenceJobQueue = Objects.requireNonNull(inferenceJobQueue, "inferenceJobQueue");
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
            if (generation.status() != GenerationStatus.COMPLETED) {
                inferenceJobQueue.enqueue(generation.id());
            }
            return new SendMessageResult(conversation, findMessage(conversation, generation.userMessageId()), generation);
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
        Generation persisted = begin.generation();
        if (!begin.created()) {
            validateReplay(persisted, command.content());
            Conversation reloaded = loadConversation(command.conversationId());
            if (persisted.status() != GenerationStatus.COMPLETED) {
                inferenceJobQueue.enqueue(persisted.id());
            }
            return new SendMessageResult(reloaded, findMessage(reloaded, persisted.userMessageId()), persisted);
        }

        inferenceJobQueue.enqueue(candidate.id());
        return new SendMessageResult(conversation, userMessage, candidate);
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
