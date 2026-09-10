package com.systemdesign.chatgpt.conversation.domain;

import java.util.Objects;

@FunctionalInterface
public interface InferenceDispatch {
    void dispatch(Generation generation);

    static InferenceDispatch noop() {
        return generation -> Objects.requireNonNull(generation, "generation");
    }
}
