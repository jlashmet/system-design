package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.InferenceDispatch;
import com.systemdesign.chatgpt.conversation.domain.InferenceQuota;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SendMessageHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    @Test
    void persistsRoutingRequirementsThenDispatchesCommittedGeneration() {
        Fixture fixture = new Fixture();

        SendMessageResult result = fixture.handler.handle(new SendMessageCommand(
                fixture.conversationId, "request-1", "hello", Set.of(ModelCapability.TOOL_CALLING)));

        assertThat(result.conversation().messages())
                .extracting(Message::role, Message::content)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(MessageRole.USER, "hello"));
        assertThat(result.generation().requiredCapabilities()).containsExactly(ModelCapability.TOOL_CALLING);
        assertThat(fixture.store.findByIdempotencyKey(fixture.conversationId, "request-1"))
                .contains(result.generation());
        assertThat(fixture.dispatch.generations).containsExactly(result.generation());
        assertThat(fixture.quota.calls).hasValue(1);
    }

    @Test
    void replayDoesNotConsumeQuotaAgainAndRedispatchesSameUnfinishedGeneration() {
        Fixture fixture = new Fixture();
        SendMessageCommand command = new SendMessageCommand(fixture.conversationId, "request-1", "hello");

        SendMessageResult first = fixture.handler.handle(command);
        SendMessageResult replay = fixture.handler.handle(command);

        assertThat(replay.generation().id()).isEqualTo(first.generation().id());
        assertThat(fixture.quota.calls).hasValue(1);
        assertThat(fixture.store.findById(fixture.conversationId).orElseThrow().messages()).hasSize(1);
        assertThat(fixture.dispatch.generations)
                .extracting(Generation::id)
                .containsExactly(first.generation().id(), first.generation().id());
    }

    @Test
    void rejectsGenerationWhenQuotaIsExhaustedBeforePersistingOrDispatchingTurn() {
        Fixture fixture = new Fixture();
        fixture.quota.accepting = false;

        assertThatThrownBy(() -> fixture.handler.handle(
                new SendMessageCommand(fixture.conversationId, "request-1", "hello")))
                .isInstanceOf(InferenceQuotaExceededException.class);

        assertThat(fixture.store.findById(fixture.conversationId).orElseThrow().messages()).isEmpty();
        assertThat(fixture.store.findByIdempotencyKey(fixture.conversationId, "request-1")).isEmpty();
        assertThat(fixture.dispatch.generations).isEmpty();
    }

    @Test
    void rejectsReuseOfIdempotencyKeyWithDifferentRoutingRequirementsBeforeRedispatch() {
        Fixture fixture = new Fixture();
        fixture.handler.handle(new SendMessageCommand(fixture.conversationId, "request-1", "hello"));

        assertThatThrownBy(() -> fixture.handler.handle(new SendMessageCommand(
                fixture.conversationId, "request-1", "hello", Set.of(ModelCapability.VISION))))
                .isInstanceOf(TurnConflictException.class)
                .hasMessageContaining("different request parameters");
        assertThat(fixture.dispatch.generations).hasSize(1);
    }

    private static final class Fixture {
        private final UUID conversationId = UUID.randomUUID();
        private final FakeStore store = new FakeStore();
        private final FakeDispatch dispatch = new FakeDispatch();
        private final FakeQuota quota = new FakeQuota();
        private final SendMessageHandler handler;

        private Fixture() {
            store.save(Conversation.start(conversationId, "user-1", NOW));
            handler = new SendMessageHandler(
                    store, store, dispatch, quota, UUID::randomUUID,
                    Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        }
    }

    private static final class FakeDispatch implements InferenceDispatch {
        private final List<Generation> generations = new ArrayList<>();
        @Override public void dispatch(Generation generation) { generations.add(generation); }
    }

    private static final class FakeQuota implements InferenceQuota {
        private final AtomicInteger calls = new AtomicInteger();
        private boolean accepting = true;
        @Override public boolean tryAcquire(String subjectId, String idempotencyKey, Instant now) {
            calls.incrementAndGet(); return accepting;
        }
    }

    private static final class FakeStore implements ConversationRepository, TurnRepository {
        private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();
        private final Map<String, Generation> generations = new ConcurrentHashMap<>();
        private final Map<UUID, Generation> generationsById = new ConcurrentHashMap<>();

        @Override public Optional<Conversation> findById(UUID id) { return Optional.ofNullable(conversations.get(id)); }
        @Override public void save(Conversation conversation) { conversations.put(conversation.id(), conversation); }
        @Override public Optional<Generation> findGenerationById(UUID id) { return Optional.ofNullable(generationsById.get(id)); }
        @Override public Optional<Generation> findByIdempotencyKey(UUID conversationId, String key) { return Optional.ofNullable(generations.get(key(conversationId, key))); }
        @Override public synchronized BeginResult begin(Conversation conversation, Generation generation) {
            String key = key(generation.conversationId(), generation.idempotencyKey());
            Generation existing = generations.get(key);
            if (existing != null) return new BeginResult(existing, false);
            conversations.put(conversation.id(), conversation); put(generation); return new BeginResult(generation, true);
        }
        @Override public synchronized Optional<Generation> claim(UUID id, Instant at) { Generation g = generationsById.get(id); if (g == null) return Optional.empty(); g = g.running(at); put(g); return Optional.of(g); }
        @Override public synchronized Optional<Generation> cancel(UUID id, Instant at) { Generation g = generationsById.get(id); if (g == null) return Optional.empty(); g = g.cancelled(at); put(g); return Optional.of(g); }
        @Override public synchronized void complete(Conversation conversation, Generation generation) { conversations.put(conversation.id(), conversation); put(generation); }
        @Override public void fail(Generation generation) { put(generation); }
        private void put(Generation generation) { generations.put(key(generation.conversationId(), generation.idempotencyKey()), generation); generationsById.put(generation.id(), generation); }
        private String key(UUID conversationId, String idempotencyKey) { return conversationId + ":" + idempotencyKey; }
    }
}
