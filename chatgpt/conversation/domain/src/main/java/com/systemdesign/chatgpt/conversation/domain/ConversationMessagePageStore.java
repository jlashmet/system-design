package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface ConversationMessagePageStore {
    Page read(UUID conversationId, int limit, String cursor);

    record Page(List<Message> messages, String nextCursor) {
        public Page {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        }
    }
}
