package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Conversation {
    private final UUID id;
    private final String userId;
    private final Instant createdAt;
    private final List<Message> messages;

    private Conversation(UUID id, String userId, Instant createdAt, List<Message> messages) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        this.userId = userId;
        this.messages = new ArrayList<>(Objects.requireNonNull(messages, "messages"));
    }

    public static Conversation start(UUID id, String userId, Instant createdAt) {
        return new Conversation(id, userId, createdAt, List.of());
    }

    public static Conversation rehydrate(UUID id, String userId, Instant createdAt, List<Message> messages) {
        return new Conversation(id, userId, createdAt, messages);
    }

    public void append(Message message) {
        Objects.requireNonNull(message, "message");
        if (message.createdAt().isBefore(createdAt)) {
            throw new IllegalArgumentException("message cannot predate conversation");
        }
        if (!messages.isEmpty() && message.createdAt().isBefore(messages.getLast().createdAt())) {
            throw new IllegalArgumentException("messages must be appended in chronological order");
        }
        messages.add(message);
    }

    public UUID id() {
        return id;
    }

    public String userId() {
        return userId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<Message> messages() {
        return List.copyOf(messages);
    }
}
