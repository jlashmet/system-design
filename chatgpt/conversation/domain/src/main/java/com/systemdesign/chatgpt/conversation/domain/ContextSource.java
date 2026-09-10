package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContextSource {
    Kind kind();

    int priority();

    List<Message> load(Conversation conversation, Generation generation);

    default Optional<UUID> replacesHistoryThrough(Conversation conversation, Generation generation) {
        return Optional.empty();
    }

    enum Kind {
        SUMMARY,
        RETRIEVAL,
        LONG_TERM_MEMORY
    }
}
