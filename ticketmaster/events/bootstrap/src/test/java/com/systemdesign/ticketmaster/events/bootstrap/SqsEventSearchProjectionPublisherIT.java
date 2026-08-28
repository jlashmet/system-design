package com.systemdesign.ticketmaster.events.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.ticketmaster.events.application.EventSearchProjection;
import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Testcontainers
class SqsEventSearchProjectionPublisherIT {
    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private SqsClient sqs;
    private String queueUrl;
    private ObjectMapper mapper;
    private SqsEventSearchProjectionPublisher publisher;
    private JsonNode receivedBody;

    @AfterEach
    void tearDown() {
        if (sqs != null) {
            if (queueUrl != null) sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            sqs.close();
        }
    }

    @Test
    void publishesVersionedProjectionToFifoQueue() {
        givenFifoProjectionQueue();
        whenProjectionIsPublished();
        thenExpectVersionedProjectionMessage();
    }

    private void givenFifoProjectionQueue() {
        sqs = SqsClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                        .queueName("event-search-" + UUID.randomUUID() + ".fifo")
                        .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))
                        .build())
                .queueUrl();
        mapper = new ObjectMapper();
        publisher = new SqsEventSearchProjectionPublisher(sqs, mapper, queueUrl);
        receivedBody = null;
    }

    private void whenProjectionIsPublished() {
        publisher.publish(new EventSearchProjection(
                "event-1",
                "Taylor Swift",
                "SoFi Stadium",
                "Los Angeles",
                Instant.parse("2026-10-10T03:00:00Z"),
                "CONCERT"), "stream-event-1");
        var messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(1)
                        .build())
                .messages();
        if (messages.size() != 1) return;
        try {
            receivedBody = mapper.readTree(messages.getFirst().body());
        } catch (Exception error) {
            throw new IllegalStateException("projection body is not valid JSON", error);
        }
    }

    private void thenExpectVersionedProjectionMessage() {
        assertThat(receivedBody).isNotNull();
        assertThat(receivedBody.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(receivedBody.get("type").asText()).isEqualTo("UPSERT");
        assertThat(receivedBody.get("eventId").asText()).isEqualTo("event-1");
        assertThat(receivedBody.get("venue").asText()).isEqualTo("SoFi Stadium");
        assertThat(receivedBody.get("startsAtEpochMillis").asLong())
                .isEqualTo(Instant.parse("2026-10-10T03:00:00Z").toEpochMilli());
    }
}
