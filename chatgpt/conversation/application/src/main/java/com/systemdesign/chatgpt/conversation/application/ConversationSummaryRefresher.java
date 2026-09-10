package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryDeltaStore;
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
    private final ConversationSummaryDeltaStore deltaStore;
    private final ConversationSummarizer summarizer;
    private final int minUnsummarizedMessages;
    private final int maxCatchupMessages;
    private final Supplier<UUID> idGenerator;
    private final Clock clock;

    public ConversationSummaryRefresher(
            ConversationSummaryStore store,
            ConversationSummarizer summarizer,
            int minUnsummarizedMessages,
            Supplier<UUID> idGenerator,
            Clock clock) {
        this(store, (conversationId, after, through, limit) -> List.of(), summarizer,
                minUnsummarizedMessages, 200, idGenerator, clock);
    }

    public ConversationSummaryRefresher(
            ConversationSummaryStore store,
            ConversationSummaryDeltaStore deltaStore,
            ConversationSummarizer summarizer,
            int minUnsummarizedMessages,
            int maxCatchupMessages,
            Supplier<UUID> idGenerator,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.deltaStore = Objects.requireNonNull(deltaStore, "deltaStore");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer");
        if (minUnsummarizedMessages < 1) throw new IllegalArgumentException("minUnsummarizedMessages must be >= 1");
        if (maxCatchupMessages < minUnsummarizedMessages) {
            throw new IllegalArgumentException("maxCatchupMessages must be >= minUnsummarizedMessages");
        }
        this.minUnsummarizedMessages = minUnsummarizedMessages;
        this.maxCatchupMessages = maxCatchupMessages;
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean refreshIfNeeded(Conversation conversation) {
        Objects.requireNonNull(conversation, "conversation");
        List<Message> messages = conversation.messages();
        if (messages.isEmpty()) return false;

        ConversationSummaryStore.Summary existing = store.find(conversation.id()).orElse(null);
        List<Message> delta;
        if (existing == null) {
            delta = messages;
        } else {
            int startIndex = indexAfter(messages, existing.throughMessageId());
            if (startIndex >= 0) {
                delta = messages.subList(startIndex, messages.size());
            } else {
                ConversationSummaryDeltaStore.Position after = existing.throughPosition().orElse(null);
                ConversationSummaryDeltaStore.Position through = ConversationSummaryDeltaStore.Position.of(messages.getLast());
                if (after == null || through.compareTo(after) <= 0) return false;
                delta = deltaStore.load(conversation.id(), after, through, maxCatchupMessages);
            }
        }

        if (delta.size() < minUnsummarizedMessages) return false;
        String previousSummary = existing == null ? "" : existing.content();
        String content = summarizer.summarize(previousSummary, delta);
        Message through = delta.getLast();
        Instant updatedAt = Instant.now(clock);
        store.save(new ConversationSummaryStore.Summary(
                idGenerator.get(), conversation.id(), through.id(), through.createdAt(), content, updatedAt));
        return true;
    }

    private int indexAfter(List<Message> messages, UUID messageId) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).id().equals(messageId)) return index + 1;
        }
        return -1;
    }
}
