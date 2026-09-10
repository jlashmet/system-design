package com.systemdesign.chatgpt.conversation.domain;

import java.util.Optional;
import java.util.UUID;

public interface InferenceJobQueue {
    void enqueue(UUID generationId);

    Optional<UUID> poll();
}
