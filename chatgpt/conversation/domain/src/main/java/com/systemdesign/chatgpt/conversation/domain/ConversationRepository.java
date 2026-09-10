package com.systemdesign.chatgpt.conversation.domain;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository {
    Optional<Conversation> findById(UUID conversationId);

    void save(Conversation conversation);
}
