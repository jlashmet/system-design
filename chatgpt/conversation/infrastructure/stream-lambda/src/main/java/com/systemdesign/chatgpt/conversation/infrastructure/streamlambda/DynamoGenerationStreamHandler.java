package com.systemdesign.chatgpt.conversation.infrastructure.streamlambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse.BatchItemFailure;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import com.systemdesign.chatgpt.conversation.infrastructure.common.InferenceJobCodec;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * DynamoDB Streams Lambda that turns newly inserted PENDING generation records into SQS inference jobs.
 *
 * <p>The event source mapping filters to INSERT records for generation items. The handler repeats those
 * checks defensively and reports failed stream records by sequence number through Lambda's partial-batch
 * response contract. Successful records are not reported for retry. Duplicate deliveries remain safe
 * because generation workers use leased/fenced claims.</p>
 */
public final class DynamoGenerationStreamHandler implements RequestHandler<DynamodbEvent, StreamsEventResponse> {
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
    public StreamsEventResponse handleRequest(DynamodbEvent event, Context context) {
        Objects.requireNonNull(event, "event");
        if (event.getRecords() == null || event.getRecords().isEmpty()) {
            return new StreamsEventResponse(List.of());
        }

        List<BatchItemFailure> failures = new ArrayList<>();
        for (DynamodbStreamRecord record : event.getRecords()) {
            try {
                enqueueIfPendingGenerationInsert(record);
            } catch (RuntimeException exception) {
                failures.add(new BatchItemFailure(sequenceNumber(record)));
            }
        }
        return new StreamsEventResponse(failures);
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

    private String sequenceNumber(DynamodbStreamRecord record) {
        if (record == null || record.getDynamodb() == null
                || record.getDynamodb().getSequenceNumber() == null
                || record.getDynamodb().getSequenceNumber().isBlank()) {
            throw new IllegalStateException("failed stream record is missing sequence number");
        }
        return record.getDynamodb().getSequenceNumber();
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
