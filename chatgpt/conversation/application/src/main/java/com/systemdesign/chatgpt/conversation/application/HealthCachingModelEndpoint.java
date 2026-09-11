package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelEndpoint;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;
import com.systemdesign.chatgpt.conversation.domain.ToolDefinition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class HealthCachingModelEndpoint implements ModelEndpoint {
    private final ModelEndpoint delegate;
    private final Duration ttl;
    private final Clock clock;
    private volatile HealthSnapshot snapshot;

    public HealthCachingModelEndpoint(ModelEndpoint delegate, Duration ttl, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("ttl must be > 0");
    }

    @Override
    public ModelProfile profile() {
        return delegate.profile();
    }

    @Override
    public boolean healthy() {
        Instant now = Instant.now(clock);
        HealthSnapshot current = snapshot;
        if (current != null && now.isBefore(current.expiresAt())) return current.healthy();
        synchronized (this) {
            current = snapshot;
            now = Instant.now(clock);
            if (current != null && now.isBefore(current.expiresAt())) return current.healthy();
            boolean healthy;
            try {
                healthy = delegate.healthy();
            } catch (RuntimeException ignored) {
                healthy = false;
            }
            snapshot = new HealthSnapshot(healthy, now.plus(ttl));
            return healthy;
        }
    }

    @Override
    public Completion complete(List<Message> messages) {
        return delegate.complete(messages);
    }

    @Override
    public Completion complete(List<Message> messages, Set<ModelCapability> requiredCapabilities) {
        return delegate.complete(messages, requiredCapabilities);
    }

    @Override
    public Completion stream(List<Message> messages, Consumer<String> deltaConsumer) {
        return delegate.stream(messages, deltaConsumer);
    }

    @Override
    public Completion stream(List<Message> messages, Set<ModelCapability> requiredCapabilities,
            Consumer<String> deltaConsumer) {
        return delegate.stream(messages, requiredCapabilities, deltaConsumer);
    }

    @Override
    public TurnResult streamTurn(List<Message> messages, Set<ModelCapability> requiredCapabilities,
            List<ToolDefinition> tools, Consumer<String> deltaConsumer) {
        return delegate.streamTurn(messages, requiredCapabilities, tools, deltaConsumer);
    }

    private record HealthSnapshot(boolean healthy, Instant expiresAt) { }
}
