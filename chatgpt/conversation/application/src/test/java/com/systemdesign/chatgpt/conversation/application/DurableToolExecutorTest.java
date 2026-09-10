package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ToolAuthorization;
import com.systemdesign.chatgpt.conversation.domain.ToolCall;
import com.systemdesign.chatgpt.conversation.domain.ToolDefinition;
import com.systemdesign.chatgpt.conversation.domain.ToolExecutionContext;
import com.systemdesign.chatgpt.conversation.domain.ToolHandler;
import com.systemdesign.chatgpt.conversation.domain.ToolInvocationStore;
import com.systemdesign.chatgpt.conversation.domain.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DurableToolExecutorTest {
    @Test
    void replaysCompletedResultWithoutExecutingSideEffectTwice() {
        UUID generationId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        ToolCall call = new ToolCall(UUID.randomUUID(), "charge", Map.of("cents", new ToolCall.IntegerValue(100)));
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<ToolExecutionContext> context = new AtomicReference<>();
        ToolHandler handler = new ToolHandler() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("charge", "charge", List.of(
                        new ToolDefinition.Parameter("cents", ToolDefinition.Type.INTEGER, true, "amount")));
            }
            @Override public ToolResult execute(ToolCall ignored) { throw new AssertionError("context overload expected"); }
            @Override public ToolResult execute(ToolCall requested, ToolExecutionContext executionContext) {
                executions.incrementAndGet(); context.set(executionContext);
                return new ToolResult(requested.id(), ToolResult.Status.SUCCESS, "charged");
            }
        };
        FakeInvocationStore store = new FakeInvocationStore();
        ToolAuthorization allow = (user, conversation, tool) -> true;
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T20:00:00Z"), ZoneOffset.UTC);
        ToolExecutor executor = new ToolExecutor(List.of(handler), allow, store, clock,
                Duration.ofSeconds(1), Duration.ofSeconds(5), 100);

        ToolResult first = executor.execute("user-1", conversationId, generationId, call);
        ToolResult replay = executor.execute("user-1", conversationId, generationId, call);

        assertThat(first).isEqualTo(replay);
        assertThat(executions).hasValue(1);
        assertThat(context.get().idempotencyKey()).isEqualTo(generationId + ":" + call.id());
        assertThat(context.get().generationId()).isEqualTo(generationId);
    }

    private static final class FakeInvocationStore implements ToolInvocationStore {
        private UUID token;
        private ToolResult result;
        @Override public Claim claim(UUID generationId, UUID callId, String toolName, String fingerprint,
                Instant now, Instant leaseUntil) {
            if (result != null) return Claim.completed(result);
            if (token != null) return Claim.busy();
            token = UUID.randomUUID(); return Claim.claimed(token);
        }
        @Override public void complete(UUID generationId, UUID callId, UUID claimToken, ToolResult completed,
                Instant completedAt) {
            if (!token.equals(claimToken)) throw new IllegalStateException("stale");
            result = completed;
        }
    }
}
