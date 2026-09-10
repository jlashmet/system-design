package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ContextAssembler;
import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummarizer;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryStore;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;
import com.systemdesign.chatgpt.conversation.domain.RunningMessageStore;
import com.systemdesign.chatgpt.conversation.domain.ToolAuthorization;
import com.systemdesign.chatgpt.conversation.domain.ToolCall;
import com.systemdesign.chatgpt.conversation.domain.ToolDefinition;
import com.systemdesign.chatgpt.conversation.domain.ToolHandler;
import com.systemdesign.chatgpt.conversation.domain.ToolResult;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
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

class ProcessGenerationHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    @Test
    void completesQueuedGenerationPublishesStreamEventsAndRefreshesSummary() {
        Fixture fixture = new Fixture(messages -> new ModelGateway.Completion("test-model", "assistant: hello"));

        fixture.handler.handle(fixture.generation.id());

        Generation completed = fixture.store.findGenerationById(fixture.generation.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(fixture.store.findById(fixture.conversationId).orElseThrow().messages())
                .extracting(Message::role, Message::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MessageRole.USER, "hello"),
                        org.assertj.core.groups.Tuple.tuple(MessageRole.ASSISTANT, "assistant: hello"));
        assertThat(fixture.eventBus.events)
                .extracting(GenerationEventBus.Event::type, GenerationEventBus.Event::data)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(GenerationEventBus.Type.DELTA, "assistant: hello"),
                        org.assertj.core.groups.Tuple.tuple(GenerationEventBus.Type.COMPLETED, ""));
        assertThat(fixture.summaryStore.find(fixture.conversationId))
                .get()
                .extracting(ConversationSummaryStore.Summary::throughMessageId, ConversationSummaryStore.Summary::content)
                .containsExactly(completed.assistantMessageId(), "summary");
    }

    @Test
    void executesPersistsAndContinuesToolRoundInSameGeneration() {
        ToolCall call = new ToolCall(UUID.randomUUID(), "lookup", Map.of("key", new ToolCall.StringValue("abc")));
        AtomicInteger modelRounds = new AtomicInteger();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Completion complete(List<Message> messages) {
                return new Completion("tool-model", "unused");
            }

            @Override
            public TurnResult streamTurn(
                    List<Message> messages,
                    Set<ModelCapability> requiredCapabilities,
                    List<ToolDefinition> tools,
                    java.util.function.Consumer<String> deltaConsumer) {
                if (modelRounds.getAndIncrement() == 0) {
                    assertThat(tools).extracting(ToolDefinition::name).containsExactly("lookup");
                    return new ToolRequests("tool-model", List.of(call));
                }
                assertThat(messages).extracting(Message::role).contains(MessageRole.TOOL);
                deltaConsumer.accept("done");
                return new FinalResponse(new Completion("tool-model", "done"));
            }
        };
        ToolHandler handler = new ToolHandler() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("lookup", "lookup", List.of(
                        new ToolDefinition.Parameter("key", ToolDefinition.Type.STRING, true, "key")));
            }

            @Override
            public ToolResult execute(ToolCall requested) {
                return new ToolResult(requested.id(), ToolResult.Status.SUCCESS, "value");
            }
        };
        Fixture fixture = new Fixture(gateway, (conversation, ignored) -> conversation.messages(),
                (previous, delta) -> "summary", List.of(handler), true, 2, true);

        fixture.handler.handle(fixture.generation.id());

        assertThat(modelRounds).hasValue(2);
        assertThat(fixture.store.findGenerationById(fixture.generation.id()).orElseThrow().status())
                .isEqualTo(GenerationStatus.COMPLETED);
        assertThat(fixture.store.findById(fixture.conversationId).orElseThrow().messages())
                .extracting(Message::role)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT);
    }

    @Test
    void stopsWhenToolRoundLimitIsExceeded() {
        ToolCall call = new ToolCall(UUID.randomUUID(), "lookup", Map.of("key", new ToolCall.StringValue("abc")));
        ModelGateway gateway = new ModelGateway() {
            @Override public Completion complete(List<Message> messages) { return new Completion("tool-model", "unused"); }
            @Override public TurnResult streamTurn(List<Message> messages, Set<ModelCapability> capabilities,
                    List<ToolDefinition> tools, java.util.function.Consumer<String> deltaConsumer) {
                return new ToolRequests("tool-model", List.of(call));
            }
        };
        ToolHandler handler = new ToolHandler() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("lookup", "lookup", List.of(
                        new ToolDefinition.Parameter("key", ToolDefinition.Type.STRING, true, "key")));
            }
            @Override public ToolResult execute(ToolCall requested) {
                return new ToolResult(requested.id(), ToolResult.Status.SUCCESS, "value");
            }
        };
        Fixture fixture = new Fixture(gateway, (conversation, ignored) -> conversation.messages(),
                (previous, delta) -> "summary", List.of(handler), true, 1, true);

        assertThatThrownBy(() -> fixture.handler.handle(fixture.generation.id()))
                .isInstanceOf(ToolRoundLimitExceededException.class);
        assertThat(fixture.store.findGenerationById(fixture.generation.id()).orElseThrow().status())
                .isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    void cancellationFencePreventsToolTranscriptPersistence() {
        ToolCall call = new ToolCall(UUID.randomUUID(), "lookup", Map.of("key", new ToolCall.StringValue("abc")));
        ModelGateway gateway = new ModelGateway() {
            @Override public Completion complete(List<Message> messages) { return new Completion("tool-model", "unused"); }
            @Override public TurnResult streamTurn(List<Message> messages, Set<ModelCapability> capabilities,
                    List<ToolDefinition> tools, java.util.function.Consumer<String> deltaConsumer) {
                return new ToolRequests("tool-model", List.of(call));
            }
        };
        ToolHandler handler = new ToolHandler() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("lookup", "lookup", List.of(
                        new ToolDefinition.Parameter("key", ToolDefinition.Type.STRING, true, "key")));
            }
            @Override public ToolResult execute(ToolCall requested) {
                return new ToolResult(requested.id(), ToolResult.Status.SUCCESS, "value");
            }
        };
        Fixture fixture = new Fixture(gateway, (conversation, ignored) -> conversation.messages(),
                (previous, delta) -> "summary", List.of(handler), true, 2, false);

        fixture.handler.handle(fixture.generation.id());

        assertThat(fixture.store.findById(fixture.conversationId).orElseThrow().messages()).hasSize(1);
        assertThat(fixture.store.findGenerationById(fixture.generation.id()).orElseThrow().status())
                .isEqualTo(GenerationStatus.CANCELLED);
    }

    @Test
    void summaryRefreshFailureDoesNotCorruptCompletedGeneration() {
        Fixture fixture = new Fixture(
                messages -> new ModelGateway.Completion("test-model", "assistant: hello"),
                (conversation, ignored) -> conversation.messages(),
                (previous, delta) -> { throw new IllegalStateException("summary unavailable"); });

        fixture.handler.handle(fixture.generation.id());

        assertThat(fixture.store.findGenerationById(fixture.generation.id()).orElseThrow().status())
                .isEqualTo(GenerationStatus.COMPLETED);
        assertThat(fixture.store.findById(fixture.conversationId).orElseThrow().messages()).hasSize(2);
        assertThat(fixture.eventBus.events.getLast()).isEqualTo(GenerationEventBus.Event.completed());
    }

    @Test
    void usesGenerationScopedContextFromAssembler() {
        List<Message> captured = new ArrayList<>();
        Fixture fixture = new Fixture(messages -> {
            captured.addAll(messages);
            return new ModelGateway.Completion("test-model", "ok");
        }, (conversation, generation) -> List.of(
                conversation.messages().stream()
                        .filter(message -> message.id().equals(generation.userMessageId()))
                        .findFirst()
                        .orElseThrow()));

        fixture.handler.handle(fixture.generation.id());

        assertThat(captured)
                .extracting(Message::role, Message::content)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(MessageRole.USER, "hello"));
    }

    @Test
    void duplicateDeliveryDoesNotInvokeProviderTwice() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = new Fixture(messages -> {
            calls.incrementAndGet();
            return new ModelGateway.Completion("test-model", "assistant: hello");
        });

        fixture.handler.handle(fixture.generation.id());
        fixture.handler.handle(fixture.generation.id());

        assertThat(calls).hasValue(1);
        assertThat(fixture.store.findById(fixture.conversationId).orElseThrow().messages()).hasSize(2);
    }

    @Test
    void marksGenerationFailedAndPublishesFailureWhenProviderFails() {
        Fixture fixture = new Fixture(messages -> {
            throw new IllegalStateException("provider unavailable");
        });

        assertThatThrownBy(() -> fixture.handler.handle(fixture.generation.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider unavailable");
        assertThat(fixture.store.findGenerationById(fixture.generation.id()).orElseThrow().status())
                .isEqualTo(GenerationStatus.FAILED);
        assertThat(fixture.eventBus.events.getLast())
                .isEqualTo(GenerationEventBus.Event.failed("provider unavailable"));
    }

    private static final class Fixture {
        private final UUID conversationId = UUID.randomUUID();
        private final UUID userMessageId = UUID.randomUUID();
        private final Generation generation;
        private final FakeStore store = new FakeStore();
        private final FakeEventBus eventBus = new FakeEventBus();
        private final FakeSummaryStore summaryStore = new FakeSummaryStore();
        private final ProcessGenerationHandler handler;

        private Fixture(ModelGateway gateway) {
            this(gateway, (conversation, ignored) -> conversation.messages());
        }

        private Fixture(ModelGateway gateway, ContextAssembler contextAssembler) {
            this(gateway, contextAssembler, (previous, delta) -> "summary");
        }

        private Fixture(ModelGateway gateway, ContextAssembler contextAssembler, ConversationSummarizer summarizer) {
            this(gateway, contextAssembler, summarizer, List.of(), false, 0, true);
        }

        private Fixture(ModelGateway gateway, ContextAssembler contextAssembler, ConversationSummarizer summarizer,
                List<ToolHandler> handlers, boolean toolsEnabled, int maxToolRounds, boolean allowTranscriptAppend) {
            Conversation conversation = Conversation.start(conversationId, "user-1", NOW);
            conversation.append(new Message(userMessageId, MessageRole.USER, "hello", NOW.plusSeconds(1)));
            generation = Generation.pending(UUID.randomUUID(), conversationId, "request-1", "hello",
                    toolsEnabled ? Set.of(ModelCapability.TOOL_CALLING) : Set.of(), userMessageId, NOW.plusSeconds(1));
            store.begin(conversation, generation);
            store.allowAppend = allowTranscriptAppend;
            Clock clock = Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC);
            ConversationSummaryRefresher refresher = new ConversationSummaryRefresher(
                    summaryStore, summarizer, 1, UUID::randomUUID, clock);
            ToolAuthorization authorization = (userId, id, toolName) -> true;
            ToolExecutor toolExecutor = new ToolExecutor(handlers, authorization, Duration.ofSeconds(1), 1000);
            handler = new ProcessGenerationHandler(
                    store, store, store, contextAssembler, gateway, eventBus, refresher,
                    toolExecutor, maxToolRounds, UUID::randomUUID, clock);
        }
    }

    private static final class FakeSummaryStore implements ConversationSummaryStore {
        private final Map<UUID, Summary> summaries = new ConcurrentHashMap<>();
        @Override public Optional<Summary> find(UUID conversationId) { return Optional.ofNullable(summaries.get(conversationId)); }
        @Override public void save(Summary summary) { summaries.put(summary.conversationId(), summary); }
    }

    private static final class FakeEventBus implements GenerationEventBus {
        private final List<Event> events = new ArrayList<>();
        @Override public void publish(UUID generationId, Event event) { events.add(event); }
        @Override public Subscription subscribe(UUID generationId, java.util.function.Consumer<Event> consumer) { return () -> { }; }
    }

    private static final class FakeStore implements ConversationRepository, TurnRepository, RunningMessageStore {
        private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();
        private final Map<UUID, Generation> generations = new ConcurrentHashMap<>();
        private final Map<String, UUID> keys = new ConcurrentHashMap<>();
        private boolean allowAppend = true;

        @Override public Optional<Conversation> findById(UUID conversationId) { return Optional.ofNullable(conversations.get(conversationId)); }
        @Override public void save(Conversation conversation) { conversations.put(conversation.id(), conversation); }
        @Override public Optional<Generation> findGenerationById(UUID generationId) { return Optional.ofNullable(generations.get(generationId)); }
        @Override public Optional<Generation> findByIdempotencyKey(UUID conversationId, String idempotencyKey) {
            UUID id = keys.get(conversationId + ":" + idempotencyKey);
            return id == null ? Optional.empty() : Optional.ofNullable(generations.get(id));
        }
        @Override public synchronized BeginResult begin(Conversation conversation, Generation generation) {
            String key = generation.conversationId() + ":" + generation.idempotencyKey();
            UUID existingId = keys.putIfAbsent(key, generation.id());
            if (existingId != null) return new BeginResult(generations.get(existingId), false);
            conversations.put(conversation.id(), conversation); generations.put(generation.id(), generation);
            return new BeginResult(generation, true);
        }
        @Override public synchronized Optional<Generation> claim(UUID generationId, Instant startedAt) {
            Generation current = generations.get(generationId);
            if (current == null || current.status() == GenerationStatus.RUNNING
                    || current.status() == GenerationStatus.COMPLETED || current.status() == GenerationStatus.CANCELLED) {
                return Optional.empty();
            }
            Generation running = current.running(startedAt); generations.put(generationId, running); return Optional.of(running);
        }
        @Override public synchronized Optional<Generation> cancel(UUID generationId, Instant cancelledAt) {
            Generation current = generations.get(generationId); if (current == null) return Optional.empty();
            Generation cancelled = current.cancelled(cancelledAt); generations.put(generationId, cancelled); return Optional.of(cancelled);
        }
        @Override public synchronized boolean append(UUID generationId, List<Message> messages) {
            if (!allowAppend) {
                Generation current = generations.get(generationId);
                generations.put(generationId, current.cancelled(NOW.plusSeconds(3)));
                return false;
            }
            Generation current = generations.get(generationId);
            if (current == null || current.status() != GenerationStatus.RUNNING) return false;
            Conversation conversation = conversations.get(current.conversationId());
            messages.forEach(conversation::append);
            return true;
        }
        @Override public void complete(Conversation conversation, Generation generation) {
            Generation current = generations.get(generation.id());
            if (current != null && current.status() == GenerationStatus.CANCELLED) return;
            conversations.put(conversation.id(), conversation); generations.put(generation.id(), generation);
        }
        @Override public void fail(Generation generation) {
            Generation current = generations.get(generation.id());
            if (current == null || current.status() != GenerationStatus.CANCELLED) generations.put(generation.id(), generation);
        }
    }
}
