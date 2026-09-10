package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;
import java.util.UUID;

public interface GenerationEventStore {
    ReplayableGenerationEventBus.RecordedEvent append(UUID generationId, GenerationEventBus.Event event);

    List<ReplayableGenerationEventBus.RecordedEvent> listAfter(UUID generationId, long afterSequence, int limit);
}
