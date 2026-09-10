package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryDeltaStore;
import com.systemdesign.chatgpt.conversation.domain.Message;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class InMemoryConversationSummaryDeltaStore implements ConversationSummaryDeltaStore {
    private final ConversationRepository repository;

    public InMemoryConversationSummaryDeltaStore(ConversationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public List<Message> load(UUID conversationId, Position afterExclusive, Position throughInclusive, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        return repository.findById(conversationId).stream()
                .flatMap(conversation -> conversation.messages().stream())
                .filter(message -> Position.of(message).compareTo(afterExclusive) > 0)
                .filter(message -> Position.of(message).compareTo(throughInclusive) <= 0)
                .sorted(java.util.Comparator.comparing(Message::createdAt).thenComparing(Message::id))
                .limit(limit)
                .toList();
    }
}
