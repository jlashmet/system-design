package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryConversationRepository implements ConversationRepository {
    private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();

    @Override
    public Optional<Conversation> findById(UUID conversationId) {
        return Optional.ofNullable(conversations.get(conversationId)).map(this::copy);
    }

    @Override
    public void save(Conversation conversation) {
        conversations.put(conversation.id(), copy(conversation));
    }

    private Conversation copy(Conversation conversation) {
        return Conversation.rehydrate(
                conversation.id(),
                conversation.userId(),
                conversation.createdAt(),
                conversation.messages());
    }
}
