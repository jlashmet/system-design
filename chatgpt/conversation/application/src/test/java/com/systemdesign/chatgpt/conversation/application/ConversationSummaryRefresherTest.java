package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryDeltaStore;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryStore;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSummaryRefresherTest {
    private static final Instant NOW = Instant.parse("2026-09-10T22:20:00Z");

    @Test
    void defersLegacySummaryWhenBoundaryPositionIsUnavailable() {
        UUID conversationId = UUID.randomUUID();
        UUID priorThrough = UUID.randomUUID();
        FakeSummaryStore store = new FakeSummaryStore();
        ConversationSummaryStore.Summary existing = new ConversationSummaryStore.Summary(
                UUID.randomUUID(), conversationId, priorThrough, "previous", NOW);
        store.save(existing);
        Conversation bounded = Conversation.start(conversationId, "user-1", NOW);
        bounded.append(new Message(UUID.randomUUID(), MessageRole.USER, "recent-1", NOW.plusSeconds(1)));
        bounded.append(new Message(UUID.randomUUID(), MessageRole.ASSISTANT, "recent-2", NOW.plusSeconds(2)));
        AtomicInteger summarizeCalls = new AtomicInteger();
        ConversationSummaryRefresher refresher = new ConversationSummaryRefresher(
                store,
                (previous, delta) -> { summarizeCalls.incrementAndGet(); return "new"; },
                1,
                UUID::randomUUID,
                Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC));

        assertThat(refresher.refreshIfNeeded(bounded)).isFalse();
        assertThat(summarizeCalls).hasValue(0);
        assertThat(store.find(conversationId)).contains(existing);
    }

    @Test
    void catchesUpFromPersistedBoundaryWhenBoundaryIsOutsideWorkerWindow() {
        UUID conversationId = UUID.randomUUID();
        Message covered = new Message(UUID.randomUUID(), MessageRole.ASSISTANT, "covered", NOW.plusSeconds(1));
        Message catchup1 = new Message(UUID.randomUUID(), MessageRole.USER, "missed-1", NOW.plusSeconds(2));
        Message catchup2 = new Message(UUID.randomUUID(), MessageRole.ASSISTANT, "missed-2", NOW.plusSeconds(3));
        Message current = new Message(UUID.randomUUID(), MessageRole.ASSISTANT, "current", NOW.plusSeconds(4));
        FakeSummaryStore store = new FakeSummaryStore();
        store.save(new ConversationSummaryStore.Summary(
                UUID.randomUUID(), conversationId, covered.id(), covered.createdAt(), "previous", NOW));
        Conversation bounded = Conversation.start(conversationId, "user-1", NOW);
        bounded.append(current);
        ConversationSummaryDeltaStore deltaStore = (id, after, through, limit) -> {
            assertThat(id).isEqualTo(conversationId);
            assertThat(after).isEqualTo(ConversationSummaryDeltaStore.Position.of(covered));
            assertThat(through).isEqualTo(ConversationSummaryDeltaStore.Position.of(current));
            assertThat(limit).isEqualTo(2);
            return List.of(catchup1, catchup2);
        };
        ConversationSummaryRefresher refresher = new ConversationSummaryRefresher(
                store, deltaStore,
                (previous, delta) -> previous + "+" + delta.stream().map(Message::content).toList(),
                1, 2, UUID::randomUUID,
                Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC));

        assertThat(refresher.refreshIfNeeded(bounded)).isTrue();
        assertThat(store.find(conversationId)).get()
                .extracting(ConversationSummaryStore.Summary::throughMessageId,
                        ConversationSummaryStore.Summary::throughMessageCreatedAt,
                        ConversationSummaryStore.Summary::content)
                .containsExactly(catchup2.id(), catchup2.createdAt(), "previous+[missed-1, missed-2]");
    }

    @Test
    void advancesWhenExistingSummaryBoundaryIsPresent() {
        UUID conversationId = UUID.randomUUID();
        Message through = new Message(UUID.randomUUID(), MessageRole.ASSISTANT, "covered", NOW.plusSeconds(1));
        Message newUser = new Message(UUID.randomUUID(), MessageRole.USER, "new", NOW.plusSeconds(2));
        FakeSummaryStore store = new FakeSummaryStore();
        store.save(new ConversationSummaryStore.Summary(
                UUID.randomUUID(), conversationId, through.id(), through.createdAt(), "previous", NOW));
        Conversation bounded = Conversation.start(conversationId, "user-1", NOW);
        bounded.append(through);
        bounded.append(newUser);
        ConversationSummaryRefresher refresher = new ConversationSummaryRefresher(
                store,
                (previous, delta) -> previous + "+" + delta.getFirst().content(),
                1,
                UUID::randomUUID,
                Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC));

        assertThat(refresher.refreshIfNeeded(bounded)).isTrue();
        assertThat(store.find(conversationId)).get()
                .extracting(ConversationSummaryStore.Summary::throughMessageId,
                        ConversationSummaryStore.Summary::throughMessageCreatedAt,
                        ConversationSummaryStore.Summary::content)
                .containsExactly(newUser.id(), newUser.createdAt(), "previous+new");
    }

    private static final class FakeSummaryStore implements ConversationSummaryStore {
        private final Map<UUID, Summary> summaries = new ConcurrentHashMap<>();
        @Override public Optional<Summary> find(UUID conversationId) { return Optional.ofNullable(summaries.get(conversationId)); }
        @Override public void save(Summary summary) { summaries.put(summary.conversationId(), summary); }
    }
}
