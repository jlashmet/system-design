package com.systemdesign.chatgpt.conversation.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public interface GenerationEventBus {
    void publish(UUID generationId, Event event);

    Subscription subscribe(UUID generationId, Consumer<Event> consumer);

    enum Type {
        DELTA,
        COMPLETED,
        FAILED
    }

    record Event(Type type, String data) {
        public Event {
            Objects.requireNonNull(type, "type");
            data = data == null ? "" : data;
        }

        public static Event delta(String text) {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("delta text must not be empty");
            }
            return new Event(Type.DELTA, text);
        }

        public static Event completed() {
            return new Event(Type.COMPLETED, "");
        }

        public static Event failed(String message) {
            return new Event(Type.FAILED, message);
        }
    }

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
