package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public interface ModelGateway {
    Completion complete(List<Message> messages);

    default Completion complete(List<Message> messages, Set<ModelCapability> requiredCapabilities) {
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        return complete(messages);
    }

    default Completion stream(List<Message> messages, Consumer<String> deltaConsumer) {
        return stream(messages, Set.of(), deltaConsumer);
    }

    default Completion stream(
            List<Message> messages,
            Set<ModelCapability> requiredCapabilities,
            Consumer<String> deltaConsumer) {
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        Objects.requireNonNull(deltaConsumer, "deltaConsumer");
        Completion completion = complete(messages, requiredCapabilities);
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
