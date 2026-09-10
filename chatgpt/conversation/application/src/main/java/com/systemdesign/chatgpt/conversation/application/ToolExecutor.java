package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ToolAuthorization;
import com.systemdesign.chatgpt.conversation.domain.ToolCall;
import com.systemdesign.chatgpt.conversation.domain.ToolDefinition;
import com.systemdesign.chatgpt.conversation.domain.ToolHandler;
import com.systemdesign.chatgpt.conversation.domain.ToolResult;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ToolExecutor {
    private final Map<String, ToolHandler> handlers;
    private final ToolAuthorization authorization;
    private final Duration timeout;
    private final int maxResultCharacters;

    public ToolExecutor(
            List<ToolHandler> handlers,
            ToolAuthorization authorization,
            Duration timeout,
            int maxResultCharacters) {
        Objects.requireNonNull(handlers, "handlers");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be > 0");
        }
        if (maxResultCharacters < 1) {
            throw new IllegalArgumentException("maxResultCharacters must be >= 1");
        }
        this.maxResultCharacters = maxResultCharacters;

        Map<String, ToolHandler> indexed = new HashMap<>();
        for (ToolHandler handler : handlers) {
            ToolHandler previous = indexed.put(handler.definition().name(), handler);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate tool name: " + handler.definition().name());
            }
        }
        this.handlers = Map.copyOf(indexed);
    }

    public List<ToolDefinition> definitionsFor(String userId, UUID conversationId) {
        return handlers.values().stream()
                .filter(handler -> authorization.isAllowed(userId, conversationId, handler.definition().name()))
                .map(ToolHandler::definition)
                .sorted(java.util.Comparator.comparing(ToolDefinition::name))
                .toList();
    }

    public ToolResult execute(String userId, UUID conversationId, ToolCall call) {
        Objects.requireNonNull(call, "call");
        ToolHandler handler = handlers.get(call.name());
        if (handler == null) {
            return new ToolResult(call.id(), ToolResult.Status.ERROR, "unknown tool: " + call.name());
        }
        if (!authorization.isAllowed(userId, conversationId, call.name())) {
            return new ToolResult(call.id(), ToolResult.Status.DENIED, "tool is not authorized");
        }

        String validationError = validate(handler.definition(), call);
        if (validationError != null) {
            return new ToolResult(call.id(), ToolResult.Status.ERROR, validationError);
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ToolResult> future = executor.submit(() -> handler.execute(call));
            try {
                ToolResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return normalize(call, result);
            } catch (TimeoutException exception) {
                future.cancel(true);
                return new ToolResult(call.id(), ToolResult.Status.TIMED_OUT, "tool execution timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                return new ToolResult(call.id(), ToolResult.Status.ERROR, "tool execution interrupted");
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                String message = cause == null || cause.getMessage() == null
                        ? "tool execution failed"
                        : cause.getMessage();
                return new ToolResult(call.id(), ToolResult.Status.ERROR, truncate(message));
            }
        }
    }

    private ToolResult normalize(ToolCall call, ToolResult result) {
        if (!call.id().equals(result.callId())) {
            return new ToolResult(call.id(), ToolResult.Status.ERROR, "tool returned mismatched call id");
        }
        return new ToolResult(result.callId(), result.status(), truncate(result.content()));
    }

    private String validate(ToolDefinition definition, ToolCall call) {
        Map<String, ToolDefinition.Parameter> parameters = definition.parameters().stream()
                .collect(java.util.stream.Collectors.toMap(ToolDefinition.Parameter::name, parameter -> parameter));
        for (String argumentName : call.arguments().keySet()) {
            if (!parameters.containsKey(argumentName)) {
                return "unknown argument: " + argumentName;
            }
        }
        for (ToolDefinition.Parameter parameter : definition.parameters()) {
            ToolCall.Value value = call.arguments().get(parameter.name());
            if (value == null) {
                if (parameter.required()) {
                    return "missing required argument: " + parameter.name();
                }
                continue;
            }
            if (!matches(parameter.type(), value)) {
                return "invalid type for argument: " + parameter.name();
            }
        }
        return null;
    }

    private boolean matches(ToolDefinition.Type type, ToolCall.Value value) {
        return switch (type) {
            case STRING -> value instanceof ToolCall.StringValue;
            case INTEGER -> value instanceof ToolCall.IntegerValue;
            case NUMBER -> value instanceof ToolCall.NumberValue || value instanceof ToolCall.IntegerValue;
            case BOOLEAN -> value instanceof ToolCall.BooleanValue;
        };
    }

    private String truncate(String value) {
        if (value.length() <= maxResultCharacters) {
            return value;
        }
        return value.substring(0, maxResultCharacters);
    }
}
