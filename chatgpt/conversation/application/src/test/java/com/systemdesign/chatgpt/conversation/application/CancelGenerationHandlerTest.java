package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class CancelGenerationHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    @Test
    void cancelsGenerationAndPublishesTerminalEvent() {
        UUID conversationId = UUID.randomUUID();
        Generation generation = Generation.pending(
                UUID.randomUUID(), conversationId, "request-1", "hello", UUID.randomUUID(), NOW);
        FakeTurnRepository repository = new FakeTurnRepository(generation);
        FakeEventBus eventBus = new FakeEventBus();
        CancelGenerationHandler handler = new CancelGenerationHandler(
                repository, eventBus, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));

        Generation result = handler.handle(conversationId, generation.id());

        assertThat(result.status()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(eventBus.events).containsExactly(GenerationEventBus.Event.cancelled());
    }

    private static final class FakeTurnRepository implements TurnRepository {
        private Generation generation;

        private FakeTurnRepository(Generation generation) {
            this.generation = generation;
        }

        @Override
        public Optional<Generation> findGenerationById(UUID generationId) {
            return generation.id().equals(generationId) ? Optional.of(generation) : Optional.empty();
        }

        @Override
        public Optional<Generation> findByIdempotencyKey(UUID conversationId, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public BeginResult begin(Conversation conversation, Generation generation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Generation> claim(UUID generationId, Instant startedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Generation> cancel(UUID generationId, Instant cancelledAt) {
            if (!generation.id().equals(generationId)) {
                return Optional.empty();
            }
            generation = generation.cancelled(cancelledAt);
            return Optional.of(generation);
        }

        @Override
        public void complete(Conversation conversation, Generation generation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fail(Generation generation) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeEventBus implements GenerationEventBus {
        private final List<Event> events = new ArrayList<>();

        @Override
        public void publish(UUID generationId, Event event) {
            events.add(event);
        }

        @Override
        public Subscription subscribe(UUID generationId, Consumer<Event> consumer) {
            return () -> { };
        }
    }
}
