package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelEndpoint;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;
import com.systemdesign.chatgpt.conversation.domain.ToolDefinition;

import java.util.Comparator;
import java.util.HashSet;
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
        return complete(messages, Set.of());
    }

    @Override
    public Completion complete(List<Message> messages, Set<ModelCapability> requiredCapabilities) {
        RuntimeException lastFailure = null;
        for (ModelEndpoint endpoint : candidates(withBase(COMPLETE_CAPABILITIES, requiredCapabilities))) {
            try {
                return endpoint.complete(messages, requiredCapabilities);
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        throw unavailable(lastFailure);
    }

    @Override
    public Completion stream(List<Message> messages, Consumer<String> deltaConsumer) {
        return stream(messages, Set.of(), deltaConsumer);
    }

    @Override
    public Completion stream(
            List<Message> messages,
            Set<ModelCapability> requiredCapabilities,
            Consumer<String> deltaConsumer) {
        TurnResult result = streamTurn(messages, requiredCapabilities, List.of(), deltaConsumer);
        if (result instanceof FinalResponse finalResponse) {
            return finalResponse.completion();
        }
        throw new ModelUnavailableException("model requested tools when no tools were available");
    }

    @Override
    public TurnResult streamTurn(
            List<Message> messages,
            Set<ModelCapability> requiredCapabilities,
            List<ToolDefinition> tools,
            Consumer<String> deltaConsumer) {
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(deltaConsumer, "deltaConsumer");
        Set<ModelCapability> capabilities = new HashSet<>(STREAM_CAPABILITIES);
        capabilities.addAll(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        if (!tools.isEmpty()) {
            capabilities.add(ModelCapability.TOOL_CALLING);
        }

        RuntimeException lastFailure = null;
        for (ModelEndpoint endpoint : candidates(Set.copyOf(capabilities))) {
            AtomicBoolean emitted = new AtomicBoolean();
            try {
                return endpoint.streamTurn(messages, requiredCapabilities, tools, delta -> {
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

    private Set<ModelCapability> withBase(
            Set<ModelCapability> base,
            Set<ModelCapability> requiredCapabilities) {
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        Set<ModelCapability> required = new HashSet<>(base);
        required.addAll(requiredCapabilities);
        return Set.copyOf(required);
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
