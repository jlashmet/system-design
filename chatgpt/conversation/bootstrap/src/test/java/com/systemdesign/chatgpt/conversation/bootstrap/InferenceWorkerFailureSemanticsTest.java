package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.application.ModelUnavailableException;
import com.systemdesign.chatgpt.conversation.application.ProcessGenerationHandler;
import com.systemdesign.chatgpt.conversation.domain.ConversationTelemetry;
import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InferenceWorkerFailureSemanticsTest {
    @Test
    void permanentModelFailureDeadLettersImmediatelyWithoutRetry() {
        InferenceJobQueue queue = mock(InferenceJobQueue.class);
        ProcessGenerationHandler handler = mock(ProcessGenerationHandler.class);
        ConversationTelemetry telemetry = mock(ConversationTelemetry.class);
        InferenceJobQueue.Job job = InferenceJobQueue.Job.firstAttempt(UUID.randomUUID());
        InferenceJobQueue.Delivery delivery = new InferenceJobQueue.Delivery(job, "receipt");
        when(queue.poll()).thenReturn(Optional.of(delivery));
        when(handler.handle(any(UUID.class), any(Runnable.class)))
                .thenThrow(new ModelUnavailableException("permanent provider rejection", false, null));

        new InferenceWorker(queue, handler, telemetry, 3).drain();

        verify(queue).deadLetter(job, "permanent provider rejection");
        verify(queue).acknowledge(delivery);
        verify(queue, never()).tryEnqueue(any(InferenceJobQueue.Job.class), any(Duration.class));
        verify(telemetry).inferenceDelivery(1, "permanent_dead_lettered");
    }

    @Test
    void retryableModelFailureUsesBoundedExponentialDelay() {
        InferenceJobQueue queue = mock(InferenceJobQueue.class);
        ProcessGenerationHandler handler = mock(ProcessGenerationHandler.class);
        ConversationTelemetry telemetry = mock(ConversationTelemetry.class);
        InferenceJobQueue.Job job = new InferenceJobQueue.Job(UUID.randomUUID(), 2);
        InferenceJobQueue.Delivery delivery = new InferenceJobQueue.Delivery(job, "receipt");
        when(queue.poll()).thenReturn(Optional.of(delivery));
        when(queue.tryEnqueue(job.nextAttempt(), Duration.ofSeconds(2))).thenReturn(true);
        when(handler.handle(any(UUID.class), any(Runnable.class)))
                .thenThrow(new ModelUnavailableException("rate limited", true, null));

        new InferenceWorker(queue, handler, telemetry, 4).drain();

        verify(queue).tryEnqueue(job.nextAttempt(), Duration.ofSeconds(2));
        verify(queue).acknowledge(delivery);
        verify(queue, never()).deadLetter(any(), anyString());
        verify(telemetry).inferenceDelivery(2, "retried");
    }

    @Test
    void retryDelayClampsAtConfiguredMaximum() {
        InferenceJobQueue queue = mock(InferenceJobQueue.class);
        ProcessGenerationHandler handler = mock(ProcessGenerationHandler.class);
        ConversationTelemetry telemetry = mock(ConversationTelemetry.class);
        InferenceJobQueue.Job job = new InferenceJobQueue.Job(UUID.randomUUID(), 5);
        InferenceJobQueue.Delivery delivery = new InferenceJobQueue.Delivery(job, "receipt");
        when(queue.poll()).thenReturn(Optional.of(delivery));
        when(queue.tryEnqueue(job.nextAttempt(), Duration.ofSeconds(3))).thenReturn(true);
        when(handler.handle(any(UUID.class), any(Runnable.class)))
                .thenThrow(new ModelUnavailableException("server unavailable", true, null));

        new InferenceWorker(queue, handler, telemetry, 6, 1000, 3000).drain();

        verify(queue).tryEnqueue(job.nextAttempt(), Duration.ofSeconds(3));
    }
}
