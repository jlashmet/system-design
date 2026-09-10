package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryStore;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryConversationSummaryStore implements ConversationSummaryStore {
    private final Map<UUID, Summary> summaries = new ConcurrentHashMap<>();

    @Override
    public Optional<Summary> find(UUID conversationId) {
        return Optional.ofNullable(summaries.get(conversationId));
    }

    @Override
    public void save(Summary summary) {
        summaries.compute(summary.conversationId(), (ignored, existing) ->
                existing == null || !existing.updatedAt().isAfter(summary.updatedAt()) ? summary : existing);
    }
}
