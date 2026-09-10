package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ContextSource;
import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.LongTermMemoryStore;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;

import java.util.List;
import java.util.Objects;

public final class LongTermMemoryContextSource implements ContextSource {
    private final LongTermMemoryStore store;
    private final int priority;
    private final int limit;

    public LongTermMemoryContextSource(LongTermMemoryStore store, int priority, int limit) {
        this.store = Objects.requireNonNull(store, "store");
        this.priority = priority;
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        this.limit = limit;
    }

    @Override
    public Kind kind() {
        return Kind.LONG_TERM_MEMORY;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public List<Message> load(Conversation conversation, Generation generation) {
        return store.list(conversation.userId(), limit).stream()
                .map(memory -> new Message(
                        memory.id(),
                        MessageRole.SYSTEM,
                        "Long-term memory:\n" + memory.content(),
                        memory.updatedAt()))
                .toList();
    }
}
