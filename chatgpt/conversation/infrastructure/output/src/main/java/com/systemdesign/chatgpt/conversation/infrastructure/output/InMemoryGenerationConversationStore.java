package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationConversationStore;
import com.systemdesign.chatgpt.conversation.domain.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryGenerationConversationStore implements GenerationConversationStore {
    private final ConversationRepository conversations;

    public InMemoryGenerationConversationStore(ConversationRepository conversations) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
    }

    @Override
    public Optional<Conversation> load(Generation generation, int maxHistoryMessages) {
        Objects.requireNonNull(generation, "generation");
        if (maxHistoryMessages < 1) throw new IllegalArgumentException("maxHistoryMessages must be >= 1");
        return conversations.findById(generation.conversationId()).map(conversation -> bounded(conversation, generation, maxHistoryMessages));
    }

    private Conversation bounded(Conversation conversation, Generation generation, int maxHistoryMessages) {
        List<Message> messages = conversation.messages();
        int target = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id().equals(generation.userMessageId())) {
                target = i;
                break;
            }
        }
        if (target < 0) throw new IllegalStateException("generation references missing user message: " + generation.userMessageId());
        int start = Math.max(0, target - maxHistoryMessages + 1);
        List<Message> bounded = new ArrayList<>(messages.subList(start, target + 1));
        return Conversation.rehydrate(conversation.id(), conversation.userId(), conversation.createdAt(), bounded);
    }
}
