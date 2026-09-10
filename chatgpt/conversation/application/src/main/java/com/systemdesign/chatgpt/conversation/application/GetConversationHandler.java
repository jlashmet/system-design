package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class GetConversationHandler {
    private final ConversationRepository repository;

    public GetConversationHandler(ConversationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Conversation handle(UUID conversationId) {
        return repository.findById(Objects.requireNonNull(conversationId, "conversationId"))
                .orElseThrow(() -> new NoSuchElementException("conversation not found: " + conversationId));
    }
}
