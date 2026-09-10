package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class SendMessageHandler {
    private final ConversationRepository repository;
    private final ModelGateway modelGateway;
    private final Supplier<UUID> idGenerator;
    private final Clock clock;

    public SendMessageHandler(
            ConversationRepository repository,
            ModelGateway modelGateway,
            Supplier<UUID> idGenerator,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SendMessageResult handle(SendMessageCommand command) {
        Objects.requireNonNull(command, "command");
        Conversation conversation = repository.findById(command.conversationId())
                .orElseThrow(() -> new NoSuchElementException("conversation not found: " + command.conversationId()));

        Instant userCreatedAt = Instant.now(clock);
        Message userMessage = new Message(idGenerator.get(), MessageRole.USER, command.content(), userCreatedAt);
        conversation.append(userMessage);

        ModelGateway.Completion completion = modelGateway.complete(conversation.messages());
        Instant assistantCreatedAt = Instant.now(clock);
        Message assistantMessage = new Message(idGenerator.get(), MessageRole.ASSISTANT, completion.content(), assistantCreatedAt);
        conversation.append(assistantMessage);

        repository.save(conversation);
        return new SendMessageResult(conversation, userMessage, assistantMessage);
    }
}
