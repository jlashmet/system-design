package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import com.systemdesign.chatgpt.conversation.domain.InferenceQuota;
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
    private final InferenceQuota inferenceQuota;
    private final Supplier<UUID> idGenerator;
    private final Clock clock;

    public SendMessageHandler(
            ConversationRepository repository,
            TurnRepository turnRepository,
            InferenceJobQueue inferenceJobQueue,
            InferenceQuota inferenceQuota,
            Supplier<UUID> idGenerator,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.turnRepository = Objects.requireNonNull(turnRepository, "turnRepository");
        this.inferenceJobQueue = Objects.requireNonNull(inferenceJobQueue, "inferenceJobQueue");
        this.inferenceQuota = Objects.requireNonNull(inferenceQuota, "inferenceQuota");
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
            validateReplay(generation, command);
            enqueueIfUnfinished(generation);
            return new SendMessageResult(conversation, findMessage(conversation, generation.userMessageId()), generation);
        }

        Instant createdAt = Instant.now(clock);
        if (!inferenceQuota.tryAcquire(conversation.userId(), command.idempotencyKey(), createdAt)) {
            throw new InferenceQuotaExceededException();
        }

        Message userMessage = new Message(idGenerator.get(), MessageRole.USER, command.content(), createdAt);
        conversation.append(userMessage);
        Generation candidate = Generation.pending(
                idGenerator.get(),
                conversation.id(),
                command.idempotencyKey(),
                command.content(),
                command.requiredCapabilities(),
                userMessage.id(),
                createdAt);

        TurnRepository.BeginResult begin = turnRepository.begin(conversation, candidate);
        Generation persisted = begin.generation();
        if (!begin.created()) {
            validateReplay(persisted, command);
            Conversation reloaded = loadConversation(command.conversationId());
            enqueueIfUnfinished(persisted);
            return new SendMessageResult(reloaded, findMessage(reloaded, persisted.userMessageId()), persisted);
        }

        enqueue(candidate);
        return new SendMessageResult(conversation, userMessage, candidate);
    }

    private void enqueueIfUnfinished(Generation generation) {
        if (generation.status() != GenerationStatus.COMPLETED
                && generation.status() != GenerationStatus.CANCELLED) {
            enqueue(generation);
        }
    }

    private void enqueue(Generation generation) {
        if (!inferenceJobQueue.tryEnqueue(InferenceJobQueue.Job.firstAttempt(generation.id()))) {
            throw new InferenceQueueSaturatedException();
        }
    }

    private void validateReplay(Generation generation, SendMessageCommand command) {
        if (!generation.requestContent().equals(command.content())
                || !generation.requiredCapabilities().equals(command.requiredCapabilities())) {
            throw new TurnConflictException("idempotency key was already used with different request parameters");
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
