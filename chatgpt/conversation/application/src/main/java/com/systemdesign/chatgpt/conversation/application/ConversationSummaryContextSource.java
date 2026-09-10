package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ContextSource;
import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryStore;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ConversationSummaryContextSource implements ContextSource {
    private final ConversationSummaryStore store;
    private final int priority;

    public ConversationSummaryContextSource(ConversationSummaryStore store, int priority) {
        this.store = Objects.requireNonNull(store, "store");
        this.priority = priority;
    }

    @Override
    public Kind kind() {
        return Kind.SUMMARY;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public List<Message> load(Conversation conversation, Generation generation) {
        return visibleSummary(conversation, generation)
                .map(summary -> List.of(new Message(
                        summary.id(),
                        MessageRole.SYSTEM,
                        "Conversation summary:\n" + summary.content(),
                        summary.updatedAt())))
                .orElseGet(List::of);
    }

    @Override
    public Optional<UUID> replacesHistoryThrough(Conversation conversation, Generation generation) {
        return visibleSummary(conversation, generation).map(ConversationSummaryStore.Summary::throughMessageId);
    }

    private Optional<ConversationSummaryStore.Summary> visibleSummary(
            Conversation conversation,
            Generation generation) {
        return store.find(conversation.id())
                .filter(summary -> isVisibleToGeneration(conversation, generation, summary));
    }

    private boolean isVisibleToGeneration(
            Conversation conversation,
            Generation generation,
            ConversationSummaryStore.Summary summary) {
        List<Message> messages = conversation.messages();
        int summaryIndex = indexOf(messages, summary.throughMessageId());
        int targetIndex = indexOf(messages, generation.userMessageId());
        return summaryIndex >= 0 && targetIndex >= 0 && summaryIndex <= targetIndex;
    }

    private int indexOf(List<Message> messages, UUID messageId) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).id().equals(messageId)) {
                return index;
            }
        }
        return -1;
    }
}
