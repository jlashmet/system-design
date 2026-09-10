package com.systemdesign.chatgpt.conversation.infrastructure.streamlambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import com.systemdesign.chatgpt.conversation.infrastructure.common.InferenceJobCodec;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * DynamoDB Streams Lambda that turns newly inserted PENDING generation records into SQS inference jobs.
 *
 * <p>The event source mapping should filter to INSERT records for generation items. The handler repeats
 * the same checks defensively so a configuration mistake cannot enqueue updates such as RUNNING or COMPLETED.
 * Any SQS exception is allowed to escape so Lambda retries the stream batch. Duplicate SQS jobs are safe
 * because generation workers use leased/fenced claims.</p>
 */
public final class DynamoGenerationStreamHandler implements RequestHandler<DynamodbEvent, Void> {
    static final String QUEUE_URL_ENV = "INFERENCE_QUEUE_URL";

    @FunctionalInterface
    interface JobSink {
        void send(InferenceJobQueue.Job job);
    }

    private final JobSink jobSink;

    public DynamoGenerationStreamHandler() {
        SqsClient sqs = SqsClient.create();
        String queueUrl = requireQueueUrl(System.getenv(QUEUE_URL_ENV));
        this.jobSink = job -> sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(InferenceJobCodec.encode(job))
                .build());
    }

    public DynamoGenerationStreamHandler(SqsClient sqs, String queueUrl) {
        Objects.requireNonNull(sqs, "sqs");
        String requiredQueueUrl = requireQueueUrl(queueUrl);
        this.jobSink = job -> sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(requiredQueueUrl)
                .messageBody(InferenceJobCodec.encode(job))
                .build());
    }

    DynamoGenerationStreamHandler(JobSink jobSink) {
        this.jobSink = Objects.requireNonNull(jobSink, "jobSink");
    }

    @Override
    public Void handleRequest(DynamodbEvent event, Context context) {
        Objects.requireNonNull(event, "event");
        if (event.getRecords() == null) return null;
        for (DynamodbStreamRecord record : event.getRecords()) {
            enqueueIfPendingGenerationInsert(record);
        }
        return null;
    }

    boolean enqueueIfPendingGenerationInsert(DynamodbStreamRecord record) {
        if (record == null || !"INSERT".equals(record.getEventName()) || record.getDynamodb() == null) {
            return false;
        }
        Map<String, AttributeValue> image = record.getDynamodb().getNewImage();
        if (!hasString(image, "entityType", "GENERATION") || !hasString(image, "status", "PENDING")) {
            return false;
        }
        String generationId = stringValue(image, "generationId");
        if (generationId == null || generationId.isBlank()) {
            throw new IllegalArgumentException("generation stream record is missing generationId");
        }

        jobSink.send(InferenceJobQueue.Job.firstAttempt(UUID.fromString(generationId)));
        return true;
    }

    private static boolean hasString(Map<String, AttributeValue> image, String name, String expected) {
        return expected.equals(stringValue(image, name));
    }

    private static String stringValue(Map<String, AttributeValue> image, String name) {
        if (image == null) return null;
        AttributeValue value = image.get(name);
        return value == null ? null : value.getS();
    }

    private static String requireQueueUrl(String queueUrl) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalArgumentException(QUEUE_URL_ENV + " must not be blank");
        }
        return queueUrl;
    }
}
