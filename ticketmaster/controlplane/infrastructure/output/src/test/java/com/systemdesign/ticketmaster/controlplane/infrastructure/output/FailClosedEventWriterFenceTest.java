package com.systemdesign.ticketmaster.controlplane.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.controlplane.domain.EventId;
import com.systemdesign.ticketmaster.controlplane.domain.RegionId;
import com.systemdesign.ticketmaster.controlplane.domain.WriterFenceNotConfirmedException;
import org.junit.jupiter.api.Test;

class FailClosedEventWriterFenceTest {
    private Throwable thrown;

    @Test
    void refusesToConfirmWriterIsolationWithoutRealFenceIntegration() {
        givenFailClosedFence();
        whenFenceIsRequested();
        thenExpectFenceNotConfirmed();
    }

    private FailClosedEventWriterFence fence;

    private void givenFailClosedFence() {
        fence = new FailClosedEventWriterFence();
        thrown = null;
    }

    private void whenFenceIsRequested() {
        try {
            fence.assertFenced(new EventId("event-123"), new RegionId("us-west-2"), 7);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectFenceNotConfirmed() {
        assertThat(thrown).isInstanceOf(WriterFenceNotConfirmedException.class)
                .hasMessageContaining("event-123")
                .hasMessageContaining("us-west-2")
                .hasMessageContaining("epoch 7");
    }
}
