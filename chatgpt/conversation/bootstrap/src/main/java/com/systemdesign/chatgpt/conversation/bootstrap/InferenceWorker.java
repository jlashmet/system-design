package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.application.ProcessGenerationHandler;
import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class InferenceWorker {
    private final InferenceJobQueue queue;
    private final ProcessGenerationHandler handler;
    private final int maxAttempts;

    public InferenceWorker(
            InferenceJobQueue queue,
            ProcessGenerationHandler handler,
            @Value("${chatgpt.inference.max-attempts:3}") int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.queue = queue;
        this.handler = handler;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${chatgpt.inference.poll-delay-ms:10}")
    public void drain() {
        queue.poll().ifPresent(this::process);
    }

    private void process(InferenceJobQueue.Job job) {
        try {
            handler.handle(job.generationId());
        } catch (RuntimeException exception) {
            if (job.attempt() >= maxAttempts) {
                queue.deadLetter(job, exception.getMessage());
                return;
            }

            InferenceJobQueue.Job retry = job.nextAttempt();
            if (!queue.tryEnqueue(retry)) {
                queue.deadLetter(retry, "retry queue saturated: " + exception.getMessage());
            }
        }
    }
}
