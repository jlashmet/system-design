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
                record("MODIFY", "GENERATION", "PENDING", UUID.randomUUID().toString()),
                record("INSERT", "MESSAGE", "PENDING", UUID.randomUUID().toString()),
                record("INSERT", "GENERATION", "RUNNING", UUID.randomUUID().toString()),
                record("INSERT", "GENERATION", "PENDING", generationId.toString())));

        handler.handleRequest(event, null);

        assertThat(jobs).containsExactly(InferenceJobQueue.Job.firstAttempt(generationId));
    }

    @Test
    void rejectsMalformedGenerationInsertWithoutId() {
        DynamoGenerationStreamHandler handler = new DynamoGenerationStreamHandler(job -> { });
        DynamodbEvent.DynamodbStreamRecord record = record("INSERT", "GENERATION", "PENDING", null);

        assertThatThrownBy(() -> handler.enqueueIfPendingGenerationInsert(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generationId");
    }

    private DynamodbEvent.DynamodbStreamRecord record(String eventName, String entityType, String status, String generationId) {
        Map<String, AttributeValue> image = new java.util.HashMap<>();
        image.put("entityType", attribute(entityType));
        image.put("status", attribute(status));
        if (generationId != null) image.put("generationId", attribute(generationId));
        StreamRecord streamRecord = new StreamRecord();
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
