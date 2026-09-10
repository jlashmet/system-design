package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
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
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SqsInferenceJobQueueIT {
    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private SqsClient sqs;
    private String queueUrl;
    private String deadLetterQueueUrl;
    private SqsInferenceJobQueue queue;

    @BeforeEach
    void setUp() {
        sqs = SqsClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        queueUrl = createQueue("chatgpt-inference-");
        deadLetterQueueUrl = createQueue("chatgpt-inference-dlq-");
        queue = new SqsInferenceJobQueue(sqs, queueUrl, deadLetterQueueUrl, 1, 0);
    }

    @AfterEach
    void tearDown() {
        if (sqs != null) {
            if (queueUrl != null) {
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            }
            if (deadLetterQueueUrl != null) {
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(deadLetterQueueUrl).build());
            }
            sqs.close();
        }
    }

    @Test
    void enqueuesPollsAndAcknowledgesDelivery() {
        InferenceJobQueue.Job expected = new InferenceJobQueue.Job(UUID.randomUUID(), 2);

        assertThat(queue.tryEnqueue(expected)).isTrue();
        InferenceJobQueue.Delivery delivery = queue.poll().orElseThrow();
        queue.acknowledge(delivery);

        assertThat(delivery.job()).isEqualTo(expected);
        assertThat(queue.poll()).isEmpty();
    }

    @Test
    void unacknowledgedDeliveryBecomesVisibleAgainAfterVisibilityTimeout() throws Exception {
        InferenceJobQueue.Job expected = InferenceJobQueue.Job.firstAttempt(UUID.randomUUID());
        queue.tryEnqueue(expected);

        InferenceJobQueue.Delivery first = queue.poll().orElseThrow();
        assertThat(queue.poll()).isEmpty();
        Thread.sleep(Duration.ofMillis(1200));
        InferenceJobQueue.Delivery redelivery = queue.poll().orElseThrow();
        queue.acknowledge(redelivery);

        assertThat(first.job()).isEqualTo(expected);
        assertThat(redelivery.job()).isEqualTo(expected);
        assertThat(redelivery.receipt()).isNotEqualTo(first.receipt());
    }

    @Test
    void deadLettersJobWithReason() {
        InferenceJobQueue.Job job = new InferenceJobQueue.Job(UUID.randomUUID(), 3);

        queue.deadLetter(job, "provider unavailable");

        var messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(deadLetterQueueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(0)
                .build()).messages();
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().body())
                .contains(job.generationId().toString())
                .contains(":3")
                .contains("provider unavailable");
    }

    private String createQueue(String prefix) {
        return sqs.createQueue(CreateQueueRequest.builder()
                .queueName(prefix + UUID.randomUUID())
                .build()).queueUrl();
    }
}
