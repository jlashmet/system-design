package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ContextSource;
import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationTelemetry;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelEndpoint;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;
import com.systemdesign.chatgpt.conversation.domain.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTelemetryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    @Test
    void recordsFailedPrimaryAndFallbackSelectionWithoutRequestIdentifiers() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        FakeEndpoint primary = new FakeEndpoint("primary", 1, true);
        primary.failure = new IllegalStateException("unavailable");
        FakeEndpoint fallback = new FakeEndpoint("fallback", 2, true);
        RoutingModelGateway router = new RoutingModelGateway(List.of(primary, fallback), telemetry);
        List<Message> messages = List.of(new Message(UUID.randomUUID(), MessageRole.USER, "hello", NOW));

        router.stream(messages, ignored -> { });

        assertThat(telemetry.routing).containsExactly(
                "primary:failed_before_output",
                "fallback:fallback_selected");
    }

    @Test
    void recordsContextTokenCompositionByBoundedSourceKind() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        TokenEstimator estimator = message -> switch (message.content()) {
            case "system" -> 2;
            case "summary" -> 3;
            case "retrieval" -> 4;
            case "memory" -> 5;
            default -> 6;
        };
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = Conversation.start(conversationId, "user-1", NOW);
        Message system = new Message(UUID.randomUUID(), MessageRole.SYSTEM, "system", NOW.plusSeconds(1));
        Message target = new Message(UUID.randomUUID(), MessageRole.USER, "target", NOW.plusSeconds(2));
        conversation.append(system);
        conversation.append(target);
        Generation generation = Generation.pending(
                UUID.randomUUID(), conversationId, "request-1", "target", target.id(), target.createdAt());

        BudgetedContextAssembler assembler = new BudgetedContextAssembler(
                estimator,
                100,
                List.of(
                        source(ContextSource.Kind.SUMMARY, 10, "summary"),
                        source(ContextSource.Kind.RETRIEVAL, 20, "retrieval"),
                        source(ContextSource.Kind.LONG_TERM_MEMORY, 30, "memory")),
                telemetry);

        assembler.assemble(conversation, generation);

        assertThat(telemetry.context).containsExactlyInAnyOrder(
                "summary:3",
                "retrieval:4",
                "long_term_memory:5",
                "system:2",
                "history:6");
    }

    private ContextSource source(ContextSource.Kind kind, int priority, String content) {
        Message message = new Message(UUID.randomUUID(), MessageRole.SYSTEM, content, NOW);
        return new ContextSource() {
            @Override public Kind kind() { return kind; }
            @Override public int priority() { return priority; }
            @Override public List<Message> load(Conversation conversation, Generation generation) { return List.of(message); }
        };
    }

    private static final class FakeEndpoint implements ModelEndpoint {
        private final ModelProfile profile;
        private final boolean healthy;
        private RuntimeException failure;

        private FakeEndpoint(String model, long cost, boolean healthy) {
            this.profile = new ModelProfile(
                    model,
                    Set.of(ModelCapability.TEXT_GENERATION, ModelCapability.STREAMING),
                    cost,
                    cost);
            this.healthy = healthy;
        }

        @Override public ModelProfile profile() { return profile; }
        @Override public boolean healthy() { return healthy; }
        @Override public Completion complete(List<Message> messages) { return new Completion(profile.model(), "ok"); }
        @Override public Completion stream(List<Message> messages, Consumer<String> deltaConsumer) {
            if (failure != null) throw failure;
            deltaConsumer.accept("ok");
            return new Completion(profile.model(), "ok");
        }
    }

    private static final class RecordingTelemetry implements ConversationTelemetry {
        private final List<String> routing = new ArrayList<>();
        private final List<String> context = new ArrayList<>();

        @Override public void generationStarted(Duration queueDelay) { }
        @Override public void firstToken(Duration timeToFirstToken) { }
        @Override public void modelRound(Duration latency, String outcome) { }
        @Override public void generationFinished(Duration endToEndLatency, String outcome) { }
        @Override public void toolInvocation(String toolName, Duration latency, String outcome) { }
        @Override public void inferenceDelivery(int attempt, String outcome) { }
        @Override public void routingDecision(String model, String outcome) { routing.add(model + ":" + outcome); }
        @Override public void contextTokens(String source, int tokens) { context.add(source + ":" + tokens); }
    }
}
