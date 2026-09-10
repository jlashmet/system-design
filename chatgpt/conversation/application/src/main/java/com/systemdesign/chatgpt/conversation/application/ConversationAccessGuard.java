package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ConversationMetadataStore;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class ConversationAccessGuard {
    private final ConversationMetadataStore metadataStore;

    public ConversationAccessGuard(ConversationMetadataStore metadataStore) {
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore");
    }

    public void requireOwner(UUID conversationId, String subjectId) {
        Objects.requireNonNull(conversationId, "conversationId");
        if (subjectId == null || subjectId.isBlank()) throw new IllegalArgumentException("subjectId must not be blank");
        ConversationMetadataStore.Metadata metadata = metadataStore.find(conversationId)
                .orElseThrow(() -> new NoSuchElementException("conversation not found: " + conversationId));
        if (!metadata.userId().equals(subjectId)) {
            // Deliberately do not reveal that another subject owns this resource.
            throw new NoSuchElementException("conversation not found: " + conversationId);
        }
    }
}
