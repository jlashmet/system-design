package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public interface ModelGateway {
    Completion complete(List<Message> messages);

    default Completion stream(List<Message> messages, Consumer<String> deltaConsumer) {
        Objects.requireNonNull(deltaConsumer, "deltaConsumer");
        Completion completion = complete(messages);
        deltaConsumer.accept(completion.content());
        return completion;
    }

    record Completion(String model, String content) {
        public Completion {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model must not be blank");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("completion content must not be blank");
            }
        }
    }
}
