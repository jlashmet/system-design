package com.systemdesign.chatgpt.conversation.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public interface ReplayableGenerationEventBus extends GenerationEventBus {
    Subscription subscribe(UUID generationId, long afterSequence, Consumer<RecordedEvent> consumer);

    record RecordedEvent(long sequence, Event event) {
        public RecordedEvent {
            if (sequence < 1) {
                throw new IllegalArgumentException("sequence must be >= 1");
            }
            Objects.requireNonNull(event, "event");
        }
    }
}
