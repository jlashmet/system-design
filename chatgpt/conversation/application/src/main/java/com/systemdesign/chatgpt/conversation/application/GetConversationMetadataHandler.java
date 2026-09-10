package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ConversationMetadataStore;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class GetConversationMetadataHandler {
    private final ConversationMetadataStore store;

    public GetConversationMetadataHandler(ConversationMetadataStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public ConversationMetadataStore.Metadata handle(UUID conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        return store.find(conversationId)
                .orElseThrow(() -> new NoSuchElementException("conversation not found: " + conversationId));
    }
}
