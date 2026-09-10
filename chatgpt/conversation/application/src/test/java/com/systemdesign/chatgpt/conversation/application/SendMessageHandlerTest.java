package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SendMessageHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    @Test
    void appendsUserAndAssistantMessages() {
        Fixture fixture = new Fixture(messages -> new ModelGateway.Completion(
                "test-model", "assistant: " + messages.getLast().content()));

        SendMessageResult result = fixture.handler.handle(
                new SendMessageCommand(fixture.conversationId, "request-1", "hello"));

        assertThat(result.conversation().messages())
                .extracting(Message::role, Message::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MessageRole.USER, "hello"),
                        org.assertj.core.groups.Tuple.tuple(MessageRole.ASSISTANT, "assistant: hello"));
    }

    @Test
    void completedReplayReturnsSameTurnWithoutCallingModelAgain() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = new Fixture(messages -> {
            calls.incrementAndGet();
            return new ModelGateway.Completion("test-model", "assistant: hello");
        });

        SendMessageCommand command = new SendMessageCommand(fixture.conversationId, "request-1", "hello");
        SendMessageResult first = fixture.handler.handle(command);
        SendMessageResult replay = fixture.handler.handle(command);

        assertThat(calls).hasValue(1);
        assertThat(replay.userMessage().id()).isEqualTo(first.userMessage().id());
        assertThat(replay.assistantMessage().id()).isEqualTo(first.assistantMessage().id());
        assertThat(replay.conversation().messages()).hasSize(2);
    }

    @Test
    void rejectsReuseOfIdempotencyKeyWithDifferentContent() {
        Fixture fixture = new Fixture(messages -> new ModelGateway.Completion("test-model", "ok"));
        fixture.handler.handle(new SendMessageCommand(fixture.conversationId, "request-1", "hello"));

        assertThatThrownBy(() -> fixture.handler.handle(
                new SendMessageCommand(fixture.conversationId, "request-1", "different")))
                .isInstanceOf(TurnConflictException.class)
                .hasMessageContaining("different content");
    }

    @Test
    void retriesFailedGenerationWithoutDuplicatingUserMessage() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = new Fixture(messages -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("provider unavailable");
            }
            return new ModelGateway.Completion("test-model", "recovered");
        });
        SendMessageCommand command = new SendMessageCommand(fixture.conversationId, "request-1", "hello");

        assertThatThrownBy(() -> fixture.handler.handle(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider unavailable");

        SendMessageResult retry = fixture.handler.handle(command);

        assertThat(calls).hasValue(2);
        assertThat(retry.conversation().messages())
                .extracting(Message::role, Message::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MessageRole.USER, "hello"),
                        org.assertj.core.groups.Tuple.tuple(MessageRole.ASSISTANT, "recovered"));
    }

    private static final class Fixture {
        private final UUID conversationId = UUID.randomUUID();
        private final FakeStore store = new FakeStore();
        private final SendMessageHandler handler;

        private Fixture(ModelGateway gateway) {
            store.save(Conversation.start(conversationId, "user-1", NOW));
            handler = new SendMessageHandler(
                    store,
                    store,
                    gateway,
                    UUID::randomUUID,
                    Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        }
    }

    private static final class FakeStore implements ConversationRepository, TurnRepository {
        private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();
        private final Map<String, Generation> generations = new ConcurrentHashMap<>();

        @Override
        public Optional<Conversation> findById(UUID conversationId) {
            return Optional.ofNullable(conversations.get(conversationId));
        }

        @Override
        public void save(Conversation conversation) {
            conversations.put(conversation.id(), conversation);
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
            generations.put(key, generation);
            return new BeginResult(generation, true);
        }

        @Override
        public synchronized void complete(Conversation conversation, Generation generation) {
            conversations.put(conversation.id(), conversation);
            generations.put(key(generation.conversationId(), generation.idempotencyKey()), generation);
        }

        @Override
        public void fail(Generation generation) {
            generations.put(key(generation.conversationId(), generation.idempotencyKey()), generation);
        }

        private String key(UUID conversationId, String idempotencyKey) {
            return conversationId + ":" + idempotencyKey;
        }
    }
}
