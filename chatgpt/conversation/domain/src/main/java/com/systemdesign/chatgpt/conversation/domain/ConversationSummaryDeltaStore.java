package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface ConversationSummaryDeltaStore {
    List<Message> load(
            UUID conversationId,
            Position afterExclusive,
            Position throughInclusive,
            int limit);

    record Position(Instant createdAt, UUID messageId) implements Comparable<Position> {
        public Position {
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(messageId, "messageId");
        }

        public static Position of(Message message) {
            Objects.requireNonNull(message, "message");
            return new Position(message.createdAt(), message.id());
        }

        @Override
        public int compareTo(Position other) {
            int time = createdAt.compareTo(other.createdAt);
            return time != 0 ? time : messageId.compareTo(other.messageId);
        }
    }
}
