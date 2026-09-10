package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqsInferenceJobQueue implements InferenceJobQueue {
    private static final String SEPARATOR = ":";

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
        Objects.requireNonNull(job, "job");
        try {
            sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(encode(job)).build());
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
        return Optional.of(new Delivery(decode(message.body()), message.receiptHandle()));
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
                .messageBody(encode(job) + "\n" + normalizedReason)
                .build());
    }

    private static String encode(Job job) { return job.generationId() + SEPARATOR + job.attempt(); }
    private static Job decode(String body) {
        String firstLine = Objects.requireNonNull(body, "body").lines().findFirst().orElse("");
        int separator = firstLine.lastIndexOf(SEPARATOR);
        if (separator <= 0 || separator == firstLine.length() - 1)
            throw new IllegalArgumentException("invalid inference job payload");
        return new Job(UUID.fromString(firstLine.substring(0, separator)),
                Integer.parseInt(firstLine.substring(separator + 1)));
    }
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
