package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ContextSource;
import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.RetrievalContextStore;

import java.util.List;
import java.util.Objects;

public final class RetrievalContextSource implements ContextSource {
    private final RetrievalContextStore store;
    private final int priority;
    private final int limit;

    public RetrievalContextSource(RetrievalContextStore store, int priority, int limit) {
        this.store = Objects.requireNonNull(store, "store");
        this.priority = priority;
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        this.limit = limit;
    }

    @Override
    public Kind kind() {
        return Kind.RETRIEVAL;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public List<Message> load(Conversation conversation, Generation generation) {
        return store.search(conversation.userId(), generation.requestContent(), limit).stream()
                .map(snippet -> new Message(
                        snippet.id(),
                        MessageRole.SYSTEM,
                        "Retrieved context:\n" + snippet.content(),
                        snippet.updatedAt()))
                .toList();
    }
}
