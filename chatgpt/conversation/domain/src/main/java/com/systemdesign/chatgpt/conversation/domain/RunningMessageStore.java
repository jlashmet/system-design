package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;
import java.util.UUID;

public interface RunningMessageStore {
    boolean append(UUID generationId, List<Message> messages);

    default boolean append(UUID generationId, UUID claimToken, List<Message> messages) {
        return append(generationId, messages);
    }
}
