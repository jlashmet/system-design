package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;
import java.util.UUID;

public interface GenerationContinuationStore {
    List<Message> list(UUID generationId);

    static GenerationContinuationStore empty() {
        return generationId -> List.of();
    }
}
