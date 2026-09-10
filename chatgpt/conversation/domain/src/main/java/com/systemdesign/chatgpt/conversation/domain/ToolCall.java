package com.systemdesign.chatgpt.conversation.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ToolCall(UUID id, String name, Map<String, Value> arguments) {
    public ToolCall {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    public sealed interface Value permits StringValue, IntegerValue, NumberValue, BooleanValue {
    }

    public record StringValue(String value) implements Value {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    public record IntegerValue(long value) implements Value {
    }

    public record NumberValue(double value) implements Value {
        public NumberValue {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("number value must be finite");
            }
        }
    }

    public record BooleanValue(boolean value) implements Value {
    }
}
