package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ToolAuthorization;
import com.systemdesign.chatgpt.conversation.domain.ToolCall;
import com.systemdesign.chatgpt.conversation.domain.ToolDefinition;
import com.systemdesign.chatgpt.conversation.domain.ToolExecutionContext;
import com.systemdesign.chatgpt.conversation.domain.ToolHandler;
import com.systemdesign.chatgpt.conversation.domain.ToolInvocationStore;
import com.systemdesign.chatgpt.conversation.domain.ToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ToolExecutor {
    private final Map<String, ToolHandler> handlers;
    private final ToolAuthorization authorization;
    private final ToolInvocationStore invocationStore;
    private final Clock clock;
    private final Duration timeout;
    private final Duration invocationLease;
    private final int maxResultCharacters;

    public ToolExecutor(
            List<ToolHandler> handlers,
            ToolAuthorization authorization,
            Duration timeout,
            int maxResultCharacters) {
        this(handlers, authorization, new ProcessLocalInvocationStore(), Clock.systemUTC(), timeout,
                timeout.plusSeconds(5), maxResultCharacters);
    }

    public ToolExecutor(
            List<ToolHandler> handlers,
            ToolAuthorization authorization,
            ToolInvocationStore invocationStore,
            Clock clock,
            Duration timeout,
            Duration invocationLease,
            int maxResultCharacters) {
        Objects.requireNonNull(handlers, "handlers");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.invocationStore = Objects.requireNonNull(invocationStore, "invocationStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = positive(timeout, "timeout");
        this.invocationLease = positive(invocationLease, "invocationLease");
        if (invocationLease.compareTo(timeout) < 0) {
            throw new IllegalArgumentException("invocationLease must be >= timeout");
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
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
    }

    public ToolResult execute(String userId, UUID conversationId, ToolCall call) {
        return execute(userId, conversationId, UUID.randomUUID(), call);
    }

    public ToolResult execute(String userId, UUID conversationId, UUID generationId, ToolCall call) {
        Objects.requireNonNull(generationId, "generationId");
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

        Instant now = Instant.now(clock);
        ToolInvocationStore.Claim claim = invocationStore.claim(
                generationId,
                call.id(),
                call.name(),
                fingerprint(call),
                now,
                now.plus(invocationLease));
        if (claim.status() == ToolInvocationStore.ClaimStatus.COMPLETED) {
            return claim.completedResult();
        }
        if (claim.status() == ToolInvocationStore.ClaimStatus.BUSY) {
            throw new ToolInvocationInProgressException(call.name());
        }

        ToolExecutionContext context = new ToolExecutionContext(
                generationId,
                call.id(),
                generationId + ":" + call.id(),
                now.plus(timeout));
        ToolResult result = executeClaimed(handler, call, context);
        invocationStore.complete(generationId, call.id(), claim.claimToken(), result, Instant.now(clock));
        return result;
    }

    private ToolResult executeClaimed(ToolHandler handler, ToolCall call, ToolExecutionContext context) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ToolResult> future = executor.submit(() -> handler.execute(call, context));
            try {
                return normalize(call, future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
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

    private String fingerprint(ToolCall call) {
        String canonical = call.name() + "|" + call.arguments().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + valueText(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String valueText(ToolCall.Value value) {
        if (value instanceof ToolCall.StringValue string) return "s:" + string.value();
        if (value instanceof ToolCall.IntegerValue integer) return "i:" + integer.value();
        if (value instanceof ToolCall.NumberValue number) return "n:" + number.value();
        if (value instanceof ToolCall.BooleanValue bool) return "b:" + bool.value();
        throw new IllegalArgumentException("unsupported tool value: " + value.getClass().getName());
    }

    private String truncate(String value) {
        if (value.length() <= maxResultCharacters) {
            return value;
        }
        return value.substring(0, maxResultCharacters);
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return duration;
    }

    private static final class ProcessLocalInvocationStore implements ToolInvocationStore {
        private final Map<String, Entry> entries = new ConcurrentHashMap<>();

        @Override
        public synchronized Claim claim(UUID generationId, UUID callId, String toolName, String requestFingerprint,
                Instant now, Instant leaseUntil) {
            String key = generationId + ":" + callId;
            Entry current = entries.get(key);
            if (current != null) {
                if (!current.toolName.equals(toolName) || !current.requestFingerprint.equals(requestFingerprint)) {
                    throw new IllegalStateException("tool call id reused with different request");
                }
                if (current.result != null) return Claim.completed(current.result);
                if (current.leaseUntil.isAfter(now)) return Claim.busy();
            }
            UUID token = UUID.randomUUID();
            entries.put(key, new Entry(toolName, requestFingerprint, token, leaseUntil, null));
            return Claim.claimed(token);
        }

        @Override
        public synchronized void complete(UUID generationId, UUID callId, UUID claimToken, ToolResult result,
                Instant completedAt) {
            String key = generationId + ":" + callId;
            Entry current = entries.get(key);
            if (current == null || !current.claimToken.equals(claimToken)) {
                throw new IllegalStateException("stale tool invocation claim");
            }
            entries.put(key, new Entry(current.toolName, current.requestFingerprint, current.claimToken,
                    current.leaseUntil, result));
        }

        private record Entry(String toolName, String requestFingerprint, UUID claimToken, Instant leaseUntil,
                ToolResult result) { }
    }
}
