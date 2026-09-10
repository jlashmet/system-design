package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;
import java.util.Objects;

public record ToolDefinition(String name, String description, List<Parameter> parameters) {
    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("tool description must not be blank");
        }
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }

    public record Parameter(String name, Type type, boolean required, String description) {
        public Parameter {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("parameter name must not be blank");
            }
            Objects.requireNonNull(type, "type");
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("parameter description must not be blank");
            }
        }
    }

    public enum Type {
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN
    }
}
