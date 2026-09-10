package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SendMessageHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    @Test
    void persistsUserMessageAndEnqueuesGenerationWithoutAssistantMessage() {
        Fixture fixture = new Fixture();

        SendMessageResult result = fixture.handler.handle(
                new SendMessageCommand(fixture.conversationId, "request-1", "hello"));

        assertThat(result.conversation().messages())
                .extracting(Message::role, Message::content)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(MessageRole.USER, "hello"));
        assertThat(result.generation().status().name()).isEqualTo("PENDING");
        assertThat(fixture.queue.poll()).contains(InferenceJobQueue.Job.firstAttempt(result.generation().id()));
    }

    @Test
    void replayReusesLogicalTurnAndRequeuesUnfinishedGeneration() {
        Fixture fixture = new Fixture();
        SendMessageCommand command = new SendMessageCommand(fixture.conversationId, "request-1", "hello");

        SendMessageResult first = fixture.handler.handle(command);
        fixture.queue.poll();
        SendMessageResult replay = fixture.handler.handle(command);

        assertThat(replay.userMessage().id()).isEqualTo(first.userMessage().id());
        assertThat(replay.generation().id()).isEqualTo(first.generation().id());
        assertThat(replay.conversation().messages()).hasSize(1);
        assertThat(fixture.queue.poll()).contains(InferenceJobQueue.Job.firstAttempt(first.generation().id()));
    }

    @Test
    void queueSaturationLeavesDurableTurnForIdempotentRetry() {
        Fixture fixture = new Fixture();
        fixture.queue.accepting = false;
        SendMessageCommand command = new SendMessageCommand(fixture.conversationId, "request-1", "hello");

        assertThatThrownBy(() -> fixture.handler.handle(command))
                .isInstanceOf(InferenceQueueSaturatedException.class);

        Generation persisted = fixture.store.findByIdempotencyKey(fixture.conversationId, "request-1").orElseThrow();
        assertThat(fixture.store.findById(fixture.conversationId).orElseThrow().messages()).hasSize(1);

        fixture.queue.accepting = true;
        SendMessageResult retry = fixture.handler.handle(command);

        assertThat(retry.generation().id()).isEqualTo(persisted.id());
        assertThat(fixture.queue.poll()).contains(InferenceJobQueue.Job.firstAttempt(persisted.id()));
        assertThat(fixture.store.findById(fixture.conversationId).orElseThrow().messages()).hasSize(1);
    }

    @Test
    void rejectsReuseOfIdempotencyKeyWithDifferentContent() {
        Fixture fixture = new Fixture();
        fixture.handler.handle(new SendMessageCommand(fixture.conversationId, "request-1", "hello"));

        assertThatThrownBy(() -> fixture.handler.handle(
                new SendMessageCommand(fixture.conversationId, "request-1", "different")))
                .isInstanceOf(TurnConflictException.class)
                .hasMessageContaining("different content");
    }

    private static final class Fixture {
        private final UUID conversationId = UUID.randomUUID();
        private final FakeStore store = new FakeStore();
        private final FakeQueue queue = new FakeQueue();
        private final SendMessageHandler handler;

        private Fixture() {
            store.save(Conversation.start(conversationId, "user-1", NOW));
            handler = new SendMessageHandler(
                    store,
                    store,
                    queue,
                    UUID::randomUUID,
                    Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        }
    }

    private static final class FakeQueue implements InferenceJobQueue {
        private final Queue<Job> jobs = new ArrayDeque<>();
        private final List<Job> deadLetters = new ArrayList<>();
        private boolean accepting = true;

        @Override
        public boolean tryEnqueue(Job job) {
            if (!accepting) {
                return false;
            }
            jobs.add(job);
            return true;
        }

        @Override
        public Optional<Job> poll() {
            return Optional.ofNullable(jobs.poll());
        }

        @Override
        public void deadLetter(Job job, String reason) {
            deadLetters.add(job);
        }
    }

    private static final class FakeStore implements ConversationRepository, TurnRepository {
        private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();
        private final Map<String, Generation> generations = new ConcurrentHashMap<>();
        private final Map<UUID, Generation> generationsById = new ConcurrentHashMap<>();

        @Override
        public Optional<Conversation> findById(UUID conversationId) {
            return Optional.ofNullable(conversations.get(conversationId));
        }

        @Override
        public void save(Conversation conversation) {
            conversations.put(conversation.id(), conversation);
        }

        @Override
        public Optional<Generation> findGenerationById(UUID generationId) {
            return Optional.ofNullable(generationsById.get(generationId));
        }

        @Override
        public Optional<Generation> findByIdempotencyKey(UUID conversationId, String idempotencyKey) {
            return Optional.ofNullable(generations.get(key(conversationId, idempotencyKey)));
        }

        @Override
        public synchronized BeginResult begin(Conversation conversation, Generation generation) {
            String key = key(generation.conversationId(), generation.idempotencyKey());
            Generation existing = generations.get(key);
            if (existing != null) {
                return new BeginResult(existing, false);
            }
            conversations.put(conversation.id(), conversation);
            put(generation);
            return new BeginResult(generation, true);
        }

        @Override
        public synchronized Optional<Generation> claim(UUID generationId, Instant startedAt) {
            Generation current = generationsById.get(generationId);
            if (current == null) {
                return Optional.empty();
            }
            Generation running = current.running(startedAt);
            put(running);
            return Optional.of(running);
        }

        @Override
        public synchronized Optional<Generation> cancel(UUID generationId, Instant cancelledAt) {
            Generation current = generationsById.get(generationId);
            if (current == null) {
                return Optional.empty();
            }
            Generation cancelled = current.cancelled(cancelledAt);
            put(cancelled);
            return Optional.of(cancelled);
        }

        @Override
        public synchronized void complete(Conversation conversation, Generation generation) {
            conversations.put(conversation.id(), conversation);
            put(generation);
        }

        @Override
        public void fail(Generation generation) {
            put(generation);
        }

        private void put(Generation generation) {
            generations.put(key(generation.conversationId(), generation.idempotencyKey()), generation);
            generationsById.put(generation.id(), generation);
        }

        private String key(UUID conversationId, String idempotencyKey) {
            return conversationId + ":" + idempotencyKey;
        }
    }
}
