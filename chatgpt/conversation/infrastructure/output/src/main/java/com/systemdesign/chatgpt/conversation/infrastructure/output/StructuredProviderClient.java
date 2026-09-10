package com.systemdesign.chatgpt.conversation.infrastructure.output;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Provider-facing protocol owned by the output adapter layer, not the domain. */
public interface StructuredProviderClient {
    Response stream(Request request, Consumer<String> textDeltaConsumer);

    record Request(String model, List<ProviderMessage> messages, List<ProviderTool> tools) {
        public Request {
            if (model == null || model.isBlank()) throw new IllegalArgumentException("model must not be blank");
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        }
    }

    record ProviderMessage(String role, String content) {
        public ProviderMessage {
            if (role == null || role.isBlank()) throw new IllegalArgumentException("role must not be blank");
            if (content == null || content.isBlank()) throw new IllegalArgumentException("content must not be blank");
        }
    }

    record ProviderTool(String name, String description, List<ProviderParameter> parameters) {
        public ProviderTool {
            parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        }
    }

    record ProviderParameter(String name, String type, boolean required, String description) { }

    sealed interface Response permits TextResponse, ToolCallResponse { }
    record TextResponse(String content) implements Response {
        public TextResponse {
            if (content == null || content.isBlank()) throw new IllegalArgumentException("content must not be blank");
        }
    }
    record ToolCallResponse(List<ProviderToolCall> calls) implements Response {
        public ToolCallResponse {
            calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
            if (calls.isEmpty()) throw new IllegalArgumentException("tool calls must not be empty");
        }
    }
    record ProviderToolCall(UUID id, String name, Map<String, ProviderValue> arguments) {
        public ProviderToolCall {
            Objects.requireNonNull(id, "id");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
        }
    }

    sealed interface ProviderValue permits StringValue, IntegerValue, NumberValue, BooleanValue { }
    record StringValue(String value) implements ProviderValue { }
    record IntegerValue(long value) implements ProviderValue { }
    record NumberValue(double value) implements ProviderValue { }
    record BooleanValue(boolean value) implements ProviderValue { }
}
