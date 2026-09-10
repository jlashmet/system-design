package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;

public interface ModelGateway {
    Completion complete(List<Message> messages);

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
