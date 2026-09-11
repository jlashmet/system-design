package com.systemdesign.chatgpt.conversation.infrastructure.streamlambda;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamoGenerationStreamHandlerTest {
    @Test
    void emitsOnlyPendingGenerationInserts() {
        UUID generationId = UUID.randomUUID();
        List<InferenceJobQueue.Job> jobs = new ArrayList<>();
        DynamoGenerationStreamHandler handler = new DynamoGenerationStreamHandler(jobs::add);
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(
                record("1", "MODIFY", "GENERATION", "PENDING", UUID.randomUUID().toString()),
                record("2", "INSERT", "MESSAGE", "PENDING", UUID.randomUUID().toString()),
                record("3", "INSERT", "GENERATION", "RUNNING", UUID.randomUUID().toString()),
                record("4", "INSERT", "GENERATION", "PENDING", generationId.toString())));

        var response = handler.handleRequest(event, null);

        assertThat(jobs).containsExactly(InferenceJobQueue.Job.firstAttempt(generationId));
        assertThat(response.getBatchItemFailures()).isEmpty();
    }

    @Test
    void reportsOnlyFailedRecordAndContinuesProcessingLaterRecords() {
        UUID first = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        List<InferenceJobQueue.Job> jobs = new ArrayList<>();
        DynamoGenerationStreamHandler handler = new DynamoGenerationStreamHandler(job -> {
            if (job.generationId().equals(failed)) throw new IllegalStateException("sqs unavailable");
            jobs.add(job);
        });
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(
                record("10", "INSERT", "GENERATION", "PENDING", first.toString()),
                record("11", "INSERT", "GENERATION", "PENDING", failed.toString()),
                record("12", "INSERT", "GENERATION", "PENDING", last.toString())));

        var response = handler.handleRequest(event, null);

        assertThat(jobs).containsExactly(
                InferenceJobQueue.Job.firstAttempt(first),
                InferenceJobQueue.Job.firstAttempt(last));
        assertThat(response.getBatchItemFailures())
                .extracting(failure -> failure.getItemIdentifier())
                .containsExactly("11");
    }

    @Test
    void malformedPendingGenerationBecomesRecordFailure() {
        DynamoGenerationStreamHandler handler = new DynamoGenerationStreamHandler(job -> { });
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record("20", "INSERT", "GENERATION", "PENDING", null)));

        var response = handler.handleRequest(event, null);

        assertThat(response.getBatchItemFailures())
                .extracting(failure -> failure.getItemIdentifier())
                .containsExactly("20");
    }

    @Test
    void failedRecordWithoutSequenceNumberFailsWholeInvocation() {
        DynamoGenerationStreamHandler handler = new DynamoGenerationStreamHandler(job -> {
            throw new IllegalStateException("sqs unavailable");
        });
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record(null, "INSERT", "GENERATION", "PENDING", UUID.randomUUID().toString())));

        assertThatThrownBy(() -> handler.handleRequest(event, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sequence number");
    }

    private DynamodbEvent.DynamodbStreamRecord record(
            String sequenceNumber, String eventName, String entityType, String status, String generationId) {
        Map<String, AttributeValue> image = new java.util.HashMap<>();
        image.put("entityType", attribute(entityType));
        image.put("status", attribute(status));
        if (generationId != null) image.put("generationId", attribute(generationId));
        StreamRecord streamRecord = new StreamRecord();
        streamRecord.setSequenceNumber(sequenceNumber);
        streamRecord.setNewImage(image);
        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName(eventName);
        record.setDynamodb(streamRecord);
        return record;
    }

    private AttributeValue attribute(String value) {
        return new AttributeValue().withS(value);
    }
}
