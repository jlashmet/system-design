package com.systemdesign.chatgpt.conversation.domain;

import java.util.Optional;

public interface GenerationConversationStore {
    Optional<Conversation> load(Generation generation, int maxHistoryMessages);
}
