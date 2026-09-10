package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ConversationListStore;

import java.util.Objects;

public final class ListConversationsHandler {
    private final ConversationListStore store;
    private final int maxPageSize;

    public ListConversationsHandler(ConversationListStore store, int maxPageSize) {
        this.store = Objects.requireNonNull(store, "store");
        if (maxPageSize < 1) throw new IllegalArgumentException("maxPageSize must be >= 1");
        this.maxPageSize = maxPageSize;
    }

    public ConversationListStore.Page handle(String subjectId, int limit, String cursor) {
        if (subjectId == null || subjectId.isBlank()) throw new IllegalArgumentException("subjectId must not be blank");
        if (limit < 1 || limit > maxPageSize) {
            throw new IllegalArgumentException("limit must be between 1 and " + maxPageSize);
        }
        return store.list(subjectId, limit, cursor);
    }
}
