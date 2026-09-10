package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ConversationSummaryStore {
    Optional<Summary> find(UUID conversationId);

    void save(Summary summary);

    record Summary(
            UUID id,
            UUID conversationId,
            UUID throughMessageId,
            Instant throughMessageCreatedAt,
            String content,
            Instant updatedAt) {
        public Summary(UUID id, UUID conversationId, UUID throughMessageId, String content, Instant updatedAt) {
            this(id, conversationId, throughMessageId, null, content, updatedAt);
        }

        public Summary {
            if (id == null || conversationId == null || throughMessageId == null || updatedAt == null) {
                throw new IllegalArgumentException("summary identifiers and updatedAt are required");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("summary content must not be blank");
            }
        }

        public Optional<ConversationSummaryDeltaStore.Position> throughPosition() {
            return throughMessageCreatedAt == null
                    ? Optional.empty()
                    : Optional.of(new ConversationSummaryDeltaStore.Position(throughMessageCreatedAt, throughMessageId));
        }
    }
}
