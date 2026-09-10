package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class CreateConversationHandler {
    private final ConversationRepository repository;
    private final Supplier<UUID> idGenerator;
    private final Clock clock;

    public CreateConversationHandler(ConversationRepository repository, Supplier<UUID> idGenerator, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Conversation handle(CreateConversationCommand command) {
        Objects.requireNonNull(command, "command");
        Conversation conversation = Conversation.start(idGenerator.get(), command.userId(), Instant.now(clock));
        repository.save(conversation);
        return conversation;
    }
}
