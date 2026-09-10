package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ToolAuthorization;
import com.systemdesign.chatgpt.conversation.domain.ToolCall;
import com.systemdesign.chatgpt.conversation.domain.ToolDefinition;
import com.systemdesign.chatgpt.conversation.domain.ToolHandler;
import com.systemdesign.chatgpt.conversation.domain.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutorTest {
    private final UUID conversationId = UUID.randomUUID();

    @Test
    void executesAuthorizedToolWithTypedArguments() {
        ToolHandler handler = echoHandler();
        ToolExecutor executor = new ToolExecutor(List.of(handler), allowAll(), Duration.ofSeconds(1), 100);
        ToolCall call = new ToolCall(UUID.randomUUID(), "echo", Map.of(
                "text", new ToolCall.StringValue("hello"),
                "count", new ToolCall.IntegerValue(2)));

        ToolResult result = executor.execute("user-1", conversationId, call);

        assertThat(result).isEqualTo(new ToolResult(call.id(), ToolResult.Status.SUCCESS, "hellohello"));
        assertThat(executor.definitionsFor("user-1", conversationId)).containsExactly(handler.definition());
    }

    @Test
    void rejectsMissingWrongTypeAndUnknownArgumentsBeforeHandlerRuns() {
        CountingHandler handler = new CountingHandler();
        ToolExecutor executor = new ToolExecutor(List.of(handler), allowAll(), Duration.ofSeconds(1), 100);

        ToolResult missing = executor.execute("user-1", conversationId,
                new ToolCall(UUID.randomUUID(), "echo", Map.of()));
        ToolResult wrongType = executor.execute("user-1", conversationId,
                new ToolCall(UUID.randomUUID(), "echo", Map.of("text", new ToolCall.BooleanValue(true))));
        ToolResult unknown = executor.execute("user-1", conversationId,
                new ToolCall(UUID.randomUUID(), "echo", Map.of(
                        "text", new ToolCall.StringValue("ok"),
                        "extra", new ToolCall.StringValue("no"))));

        assertThat(missing.status()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(missing.content()).contains("missing required argument: text");
        assertThat(wrongType.content()).contains("invalid type for argument: text");
        assertThat(unknown.content()).contains("unknown argument: extra");
        assertThat(handler.calls).isZero();
    }

    @Test
    void deniesUnauthorizedToolWithoutExecutingIt() {
        CountingHandler handler = new CountingHandler();
        ToolAuthorization denyAll = (userId, id, toolName) -> false;
        ToolExecutor executor = new ToolExecutor(List.of(handler), denyAll, Duration.ofSeconds(1), 100);
        ToolCall call = new ToolCall(UUID.randomUUID(), "echo", Map.of("text", new ToolCall.StringValue("hello")));

        ToolResult result = executor.execute("user-1", conversationId, call);

        assertThat(result.status()).isEqualTo(ToolResult.Status.DENIED);
        assertThat(handler.calls).isZero();
        assertThat(executor.definitionsFor("user-1", conversationId)).isEmpty();
    }

    @Test
    void timesOutSlowToolAndBoundsLargeResults() {
        ToolHandler slow = new ToolHandler() {
            @Override public ToolDefinition definition() { return definition("slow", List.of()); }
            @Override public ToolResult execute(ToolCall call) {
                try {
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return new ToolResult(call.id(), ToolResult.Status.SUCCESS, "late");
            }
        };
        ToolHandler large = new ToolHandler() {
            @Override public ToolDefinition definition() { return definition("large", List.of()); }
            @Override public ToolResult execute(ToolCall call) {
                return new ToolResult(call.id(), ToolResult.Status.SUCCESS, "1234567890");
            }
        };

        ToolResult timedOut = new ToolExecutor(List.of(slow), allowAll(), Duration.ofMillis(20), 100)
                .execute("user-1", conversationId, new ToolCall(UUID.randomUUID(), "slow", Map.of()));
        ToolResult truncated = new ToolExecutor(List.of(large), allowAll(), Duration.ofSeconds(1), 5)
                .execute("user-1", conversationId, new ToolCall(UUID.randomUUID(), "large", Map.of()));

        assertThat(timedOut.status()).isEqualTo(ToolResult.Status.TIMED_OUT);
        assertThat(truncated.content()).isEqualTo("12345");
    }

    private ToolHandler echoHandler() {
        return new ToolHandler() {
            @Override public ToolDefinition definition() {
                return definition("echo", List.of(
                        new ToolDefinition.Parameter("text", ToolDefinition.Type.STRING, true, "Text to echo"),
                        new ToolDefinition.Parameter("count", ToolDefinition.Type.INTEGER, false, "Repeat count")));
            }

            @Override public ToolResult execute(ToolCall call) {
                String text = ((ToolCall.StringValue) call.arguments().get("text")).value();
                ToolCall.IntegerValue count = (ToolCall.IntegerValue) call.arguments().get("count");
                int repeats = count == null ? 1 : Math.toIntExact(count.value());
                return new ToolResult(call.id(), ToolResult.Status.SUCCESS, text.repeat(repeats));
            }
        };
    }

    private ToolAuthorization allowAll() {
        return (userId, id, toolName) -> true;
    }

    private ToolDefinition definition(String name, List<ToolDefinition.Parameter> parameters) {
        return new ToolDefinition(name, name + " tool", parameters);
    }

    private final class CountingHandler implements ToolHandler {
        private int calls;

        @Override public ToolDefinition definition() {
            return definition("echo", List.of(
                    new ToolDefinition.Parameter("text", ToolDefinition.Type.STRING, true, "Text to echo")));
        }

        @Override public ToolResult execute(ToolCall call) {
            calls++;
            return new ToolResult(call.id(), ToolResult.Status.SUCCESS, "ok");
        }
    }
}
