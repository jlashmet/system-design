package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.domain.ConversationTelemetry;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.Objects;

public final class MicrometerConversationTelemetry implements ConversationTelemetry {
    private final MeterRegistry registry;

    public MicrometerConversationTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override public void generationStarted(Duration queueDelay) {
        registry.timer("chatgpt.generation.queue.delay").record(nonNegative(queueDelay));
    }

    @Override public void firstToken(Duration timeToFirstToken) {
        registry.timer("chatgpt.generation.ttft").record(nonNegative(timeToFirstToken));
    }

    @Override public void modelRound(Duration latency, String outcome) {
        registry.timer("chatgpt.model.round.latency", "outcome", bounded(outcome)).record(nonNegative(latency));
    }

    @Override public void generationFinished(Duration latency, String outcome) {
        registry.timer("chatgpt.generation.end_to_end", "outcome", bounded(outcome)).record(nonNegative(latency));
        registry.counter("chatgpt.generation.finished", "outcome", bounded(outcome)).increment();
    }

    @Override public void toolInvocation(String toolName, Duration latency, String outcome) {
        registry.timer("chatgpt.tool.latency", "tool", bounded(toolName), "outcome", bounded(outcome))
                .record(nonNegative(latency));
    }

    private Duration nonNegative(Duration value) {
        return value.isNegative() ? Duration.ZERO : value;
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
