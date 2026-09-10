package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ConversationMessagePageStore;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Message;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class InMemoryConversationMessagePageStore implements ConversationMessagePageStore {
    private final ConversationRepository conversations;

    public InMemoryConversationMessagePageStore(ConversationRepository conversations) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
    }

    @Override
    public Page read(UUID conversationId, int limit, String cursor) {
        List<Message> messages = conversations.findById(conversationId)
                .map(conversation -> conversation.messages())
                .orElse(List.of());
        int start = decode(cursor);
        if (start < 0 || start > messages.size()) throw new IllegalArgumentException("invalid message cursor");
        int end = Math.min(start + limit, messages.size());
        String next = end < messages.size() ? encode(end) : null;
        return new Page(messages.subList(start, end), next);
    }

    private String encode(int index) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(index).getBytes(StandardCharsets.UTF_8));
    }

    private int decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            return Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid message cursor", exception);
        }
    }
}
