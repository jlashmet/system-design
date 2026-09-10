package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;
import java.util.UUID;

public interface RunningMessageStore {
    boolean append(UUID generationId, List<Message> messages);
}
