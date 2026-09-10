package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummarizer;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryStore;
import com.systemdesign.chatgpt.conversation.domain.Message;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class ConversationSummaryRefresher {
    private final ConversationSummaryStore store;
    private final ConversationSummarizer summarizer;
    private final int minUnsummarizedMessages;
    private final Supplier<UUID> idGenerator;
    private final Clock clock;

    public ConversationSummaryRefresher(
            ConversationSummaryStore store,
            ConversationSummarizer summarizer,
            int minUnsummarizedMessages,
            Supplier<UUID> idGenerator,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer");
        if (minUnsummarizedMessages < 1) {
            throw new IllegalArgumentException("minUnsummarizedMessages must be >= 1");
        }
        this.minUnsummarizedMessages = minUnsummarizedMessages;
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean refreshIfNeeded(Conversation conversation) {
        Objects.requireNonNull(conversation, "conversation");
        List<Message> messages = conversation.messages();
        if (messages.isEmpty()) return false;

        ConversationSummaryStore.Summary existing = store.find(conversation.id()).orElse(null);
        int startIndex = 0;
        if (existing != null) {
            startIndex = indexAfter(messages, existing.throughMessageId());
            if (startIndex < 0) {
                // The caller supplied a bounded/truncated history that does not prove continuity from
                // the persisted summary. Restarting at zero would summarize already-covered history again.
                return false;
            }
        }

        List<Message> delta = messages.subList(startIndex, messages.size());
        if (delta.size() < minUnsummarizedMessages) return false;

        String previousSummary = existing == null ? "" : existing.content();
        String content = summarizer.summarize(previousSummary, delta);
        Message through = messages.getLast();
        Instant updatedAt = Instant.now(clock);
        store.save(new ConversationSummaryStore.Summary(
                idGenerator.get(),
                conversation.id(),
                through.id(),
                content,
                updatedAt));
        return true;
    }

    private int indexAfter(List<Message> messages, UUID messageId) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).id().equals(messageId)) return index + 1;
        }
        return -1;
    }
}
