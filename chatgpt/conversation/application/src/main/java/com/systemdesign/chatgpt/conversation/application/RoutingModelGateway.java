package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelEndpoint;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class RoutingModelGateway implements ModelGateway {
    private static final Set<ModelCapability> COMPLETE_CAPABILITIES = Set.of(ModelCapability.TEXT_GENERATION);
    private static final Set<ModelCapability> STREAM_CAPABILITIES = Set.of(
            ModelCapability.TEXT_GENERATION,
            ModelCapability.STREAMING);

    private final List<ModelEndpoint> endpoints;

    public RoutingModelGateway(List<ModelEndpoint> endpoints) {
        this.endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
        if (this.endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one model endpoint is required");
        }
    }

    @Override
    public Completion complete(List<Message> messages) {
        RuntimeException lastFailure = null;
        for (ModelEndpoint endpoint : candidates(COMPLETE_CAPABILITIES)) {
            try {
                return endpoint.complete(messages);
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        throw unavailable(lastFailure);
    }

    @Override
    public Completion stream(List<Message> messages, Consumer<String> deltaConsumer) {
        Objects.requireNonNull(deltaConsumer, "deltaConsumer");
        RuntimeException lastFailure = null;
        for (ModelEndpoint endpoint : candidates(STREAM_CAPABILITIES)) {
            AtomicBoolean emitted = new AtomicBoolean();
            try {
                return endpoint.stream(messages, delta -> {
                    emitted.set(true);
                    deltaConsumer.accept(delta);
                });
            } catch (RuntimeException exception) {
                if (emitted.get()) {
                    throw exception;
                }
                lastFailure = exception;
            }
        }
        throw unavailable(lastFailure);
    }

    private List<ModelEndpoint> candidates(Set<ModelCapability> requiredCapabilities) {
        return endpoints.stream()
                .filter(ModelEndpoint::healthy)
                .filter(endpoint -> endpoint.profile().supports(requiredCapabilities))
                .sorted(Comparator.comparingLong(endpoint -> endpoint.profile().relativeCost()))
                .toList();
    }

    private RuntimeException unavailable(RuntimeException lastFailure) {
        if (lastFailure == null) {
            return new ModelUnavailableException("no healthy model supports the required capabilities");
        }
        return new ModelUnavailableException("all eligible model providers failed", lastFailure);
    }
}
