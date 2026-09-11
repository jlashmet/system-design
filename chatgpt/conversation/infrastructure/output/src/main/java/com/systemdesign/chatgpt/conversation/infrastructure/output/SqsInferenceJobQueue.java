package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import com.systemdesign.chatgpt.conversation.infrastructure.common.InferenceJobCodec;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class SqsInferenceJobQueue implements InferenceJobQueue {
    private static final int MAX_DELAY_SECONDS = 900;
    private final SqsClient sqs;
    private final String queueUrl;
    private final String deadLetterQueueUrl;
    private final int visibilityTimeoutSeconds;
    private final int waitTimeSeconds;

    public SqsInferenceJobQueue(
            SqsClient sqs,
            String queueUrl,
            String deadLetterQueueUrl,
            int visibilityTimeoutSeconds,
            int waitTimeSeconds) {
        this.sqs = Objects.requireNonNull(sqs, "sqs");
        this.queueUrl = requireText(queueUrl, "queueUrl");
        this.deadLetterQueueUrl = requireText(deadLetterQueueUrl, "deadLetterQueueUrl");
        if (visibilityTimeoutSeconds < 1) throw new IllegalArgumentException("visibilityTimeoutSeconds must be >= 1");
        if (waitTimeSeconds < 0 || waitTimeSeconds > 20)
            throw new IllegalArgumentException("waitTimeSeconds must be between 0 and 20");
        this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
        this.waitTimeSeconds = waitTimeSeconds;
    }

    @Override
    public boolean tryEnqueue(Job job) {
        return tryEnqueue(job, Duration.ZERO);
    }

    @Override
    public boolean tryEnqueue(Job job, Duration delay) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) throw new IllegalArgumentException("delay must not be negative");
        long millis = delay.toMillis();
        long delaySeconds = millis == 0 ? 0 : Math.max(1, (millis + 999) / 1000);
        if (delaySeconds > MAX_DELAY_SECONDS) throw new IllegalArgumentException("SQS delay must not exceed 900 seconds");
        try {
            sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl)
                    .messageBody(InferenceJobCodec.encode(job))
                    .delaySeconds((int) delaySeconds)
                    .build());
            return true;
        } catch (SqsException exception) {
            return false;
        }
    }

    @Override
    public Optional<Delivery> poll() {
        var response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl).maxNumberOfMessages(1)
                .visibilityTimeout(visibilityTimeoutSeconds).waitTimeSeconds(waitTimeSeconds).build());
        if (response.messages().isEmpty()) return Optional.empty();
        Message message = response.messages().getFirst();
        return Optional.of(new Delivery(InferenceJobCodec.decode(message.body()), message.receiptHandle()));
    }

    @Override
    public void acknowledge(Delivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl).receiptHandle(delivery.receipt()).build());
    }

    @Override
    public void renew(Delivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(delivery.receipt())
                .visibilityTimeout(visibilityTimeoutSeconds)
                .build());
    }

    @Override
    public void deadLetter(Job job, String reason) {
        Objects.requireNonNull(job, "job");
        String normalizedReason = reason == null ? "" : reason;
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(deadLetterQueueUrl)
                .messageBody(InferenceJobCodec.encode(job) + "\n" + normalizedReason)
                .build());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
