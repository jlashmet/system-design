package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
import com.systemdesign.chatgpt.conversation.domain.ReplayableGenerationEventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryGenerationEventBusTest {
    @Test
    void replaysOnlyEventsAfterCursorThenContinuesLiveInOrder() {
        InMemoryGenerationEventBus bus = new InMemoryGenerationEventBus();
        UUID generationId = UUID.randomUUID();
        bus.publish(generationId, GenerationEventBus.Event.delta("one"));
        bus.publish(generationId, GenerationEventBus.Event.delta("two"));
        List<ReplayableGenerationEventBus.RecordedEvent> received = new ArrayList<>();

        GenerationEventBus.Subscription subscription = bus.subscribe(generationId, 1, received::add);
        bus.publish(generationId, GenerationEventBus.Event.completed());
        subscription.close();

        assertThat(received)
                .extracting(ReplayableGenerationEventBus.RecordedEvent::sequence)
                .containsExactly(2L, 3L);
        assertThat(received)
                .extracting(recorded -> recorded.event().type(), recorded -> recorded.event().data())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(GenerationEventBus.Type.DELTA, "two"),
                        org.assertj.core.groups.Tuple.tuple(GenerationEventBus.Type.COMPLETED, ""));
    }

    @Test
    void legacySubscriptionRemainsLiveOnly() {
        InMemoryGenerationEventBus bus = new InMemoryGenerationEventBus();
        UUID generationId = UUID.randomUUID();
        bus.publish(generationId, GenerationEventBus.Event.delta("before"));
        List<GenerationEventBus.Event> received = new ArrayList<>();

        GenerationEventBus.Subscription subscription = bus.subscribe(generationId, received::add);
        bus.publish(generationId, GenerationEventBus.Event.delta("after"));
        subscription.close();

        assertThat(received).containsExactly(GenerationEventBus.Event.delta("after"));
    }
}
