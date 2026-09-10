package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ConversationMessagePageStore;

import java.util.Objects;
import java.util.UUID;

public final class GetConversationMessagesHandler {
    private final ConversationMessagePageStore store;
    private final int maxPageSize;

    public GetConversationMessagesHandler(ConversationMessagePageStore store, int maxPageSize) {
        this.store = Objects.requireNonNull(store, "store");
        if (maxPageSize < 1) throw new IllegalArgumentException("maxPageSize must be >= 1");
        this.maxPageSize = maxPageSize;
    }

    public ConversationMessagePageStore.Page handle(UUID conversationId, int limit, String cursor) {
        Objects.requireNonNull(conversationId, "conversationId");
        if (limit < 1 || limit > maxPageSize) {
            throw new IllegalArgumentException("limit must be between 1 and " + maxPageSize);
        }
        return store.read(conversationId, limit, cursor);
    }
}
