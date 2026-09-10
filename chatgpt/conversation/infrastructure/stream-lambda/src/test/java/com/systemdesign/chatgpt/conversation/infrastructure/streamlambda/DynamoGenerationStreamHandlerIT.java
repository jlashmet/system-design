package com.systemdesign.chatgpt.conversation.infrastructure.streamlambda;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import com.systemdesign.chatgpt.conversation.infrastructure.common.InferenceJobCodec;
import io.floci.testcontainers.FlociContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DynamoGenerationStreamHandlerIT {
    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private SqsClient sqs;
    private String queueUrl;

    @BeforeEach
    void setUp() {
        sqs = SqsClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName("chatgpt-stream-lambda-" + UUID.randomUUID())
                .build()).queueUrl();
    }

    @AfterEach
    void tearDown() {
        if (sqs != null) {
            if (queueUrl != null) sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            sqs.close();
        }
    }

    @Test
    void pendingGenerationInsertProducesWorkerCompatibleSqsJob() {
        UUID generationId = UUID.randomUUID();
        DynamoGenerationStreamHandler handler = new DynamoGenerationStreamHandler(sqs, queueUrl);
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(record(generationId)));

        handler.handleRequest(event, null);

        var messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(0)
                .build()).messages();
        assertThat(messages).hasSize(1);
        assertThat(InferenceJobCodec.decode(messages.getFirst().body()))
                .isEqualTo(InferenceJobQueue.Job.firstAttempt(generationId));
    }

    private DynamodbEvent.DynamodbStreamRecord record(UUID generationId) {
        StreamRecord stream = new StreamRecord();
        stream.setNewImage(Map.of(
                "entityType", attribute("GENERATION"),
                "status", attribute("PENDING"),
                "generationId", attribute(generationId.toString())));
        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName("INSERT");
        record.setDynamodb(stream);
        return record;
    }

    private AttributeValue attribute(String value) {
        return new AttributeValue().withS(value);
    }
}
