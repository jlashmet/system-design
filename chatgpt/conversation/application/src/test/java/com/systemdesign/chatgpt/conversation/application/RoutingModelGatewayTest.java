package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelEndpoint;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingModelGatewayTest {
    private final List<Message> messages = List.of(
            new Message(UUID.randomUUID(), MessageRole.USER, "hello", Instant.parse("2026-09-10T12:00:00Z")));

    @Test
    void choosesCheapestHealthyEligibleProvider() {
        FakeEndpoint expensive = endpoint("expensive", 20, true, Set.of(ModelCapability.TEXT_GENERATION));
        FakeEndpoint unhealthyCheap = endpoint("unhealthy", 1, false, Set.of(ModelCapability.TEXT_GENERATION));
        FakeEndpoint cheap = endpoint("cheap", 5, true, Set.of(ModelCapability.TEXT_GENERATION));
        RoutingModelGateway router = new RoutingModelGateway(List.of(expensive, unhealthyCheap, cheap));

        ModelGateway.Completion completion = router.complete(messages);

        assertThat(completion.model()).isEqualTo("cheap");
        assertThat(cheap.calls).hasValue(1);
        assertThat(expensive.calls).hasValue(0);
        assertThat(unhealthyCheap.calls).hasValue(0);
    }

    @Test
    void streamingRequiresStreamingCapability() {
        FakeEndpoint textOnly = endpoint("text-only", 1, true, Set.of(ModelCapability.TEXT_GENERATION));
        FakeEndpoint streaming = endpoint(
                "streaming",
                10,
                true,
                Set.of(ModelCapability.TEXT_GENERATION, ModelCapability.STREAMING));
        RoutingModelGateway router = new RoutingModelGateway(List.of(textOnly, streaming));
        List<String> deltas = new ArrayList<>();

        ModelGateway.Completion completion = router.stream(messages, deltas::add);

        assertThat(completion.model()).isEqualTo("streaming");
        assertThat(deltas).containsExactly("streaming-answer");
        assertThat(textOnly.calls).hasValue(0);
    }

    @Test
    void fallsBackWhenProviderFailsBeforeFirstDelta() {
        FakeEndpoint primary = endpoint(
                "primary",
                1,
                true,
                Set.of(ModelCapability.TEXT_GENERATION, ModelCapability.STREAMING));
        primary.failure = new IllegalStateException("primary unavailable");
        FakeEndpoint fallback = endpoint(
                "fallback",
                2,
                true,
                Set.of(ModelCapability.TEXT_GENERATION, ModelCapability.STREAMING));
        RoutingModelGateway router = new RoutingModelGateway(List.of(primary, fallback));

        ModelGateway.Completion completion = router.stream(messages, ignored -> { });

        assertThat(completion.model()).isEqualTo("fallback");
        assertThat(primary.calls).hasValue(1);
        assertThat(fallback.calls).hasValue(1);
    }

    @Test
    void doesNotMixProvidersAfterStreamingHasStarted() {
        FakeEndpoint primary = endpoint(
                "primary",
                1,
                true,
                Set.of(ModelCapability.TEXT_GENERATION, ModelCapability.STREAMING));
        primary.emitBeforeFailure = true;
        primary.failure = new IllegalStateException("stream interrupted");
        FakeEndpoint fallback = endpoint(
                "fallback",
                2,
                true,
                Set.of(ModelCapability.TEXT_GENERATION, ModelCapability.STREAMING));
        RoutingModelGateway router = new RoutingModelGateway(List.of(primary, fallback));
        List<String> deltas = new ArrayList<>();

        assertThatThrownBy(() -> router.stream(messages, deltas::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stream interrupted");
        assertThat(deltas).containsExactly("partial");
        assertThat(fallback.calls).hasValue(0);
    }

    private FakeEndpoint endpoint(String model, long cost, boolean healthy, Set<ModelCapability> capabilities) {
        return new FakeEndpoint(new ModelProfile(model, capabilities, cost, cost), healthy);
    }

    private static final class FakeEndpoint implements ModelEndpoint {
        private final ModelProfile profile;
        private final boolean healthy;
        private final AtomicInteger calls = new AtomicInteger();
        private RuntimeException failure;
        private boolean emitBeforeFailure;

        private FakeEndpoint(ModelProfile profile, boolean healthy) {
            this.profile = profile;
            this.healthy = healthy;
        }

        @Override
        public ModelProfile profile() {
            return profile;
        }

        @Override
        public boolean healthy() {
            return healthy;
        }

        @Override
        public Completion complete(List<Message> messages) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return new Completion(profile.model(), profile.model() + "-answer");
        }

        @Override
        public Completion stream(List<Message> messages, Consumer<String> deltaConsumer) {
            calls.incrementAndGet();
            if (emitBeforeFailure) {
                deltaConsumer.accept("partial");
            }
            if (failure != null) {
                throw failure;
            }
            String content = profile.model() + "-answer";
            deltaConsumer.accept(content);
            return new Completion(profile.model(), content);
        }
    }
}
