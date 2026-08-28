package com.systemdesign.ticketmaster.events.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.ticketmaster.events.application.DeleteEventSearchProjection;
import com.systemdesign.ticketmaster.events.application.EventSearchProjection;
import com.systemdesign.ticketmaster.events.application.EventSearchProjectionAction;
import java.util.Objects;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

final class SqsEventSearchProjectionPublisher implements EventSearchProjectionPublisher {
    private static final int SCHEMA_VERSION = 1;

    private final SqsClient sqs;
    private final ObjectMapper objectMapper;
    private final String queueUrl;

    SqsEventSearchProjectionPublisher(SqsClient sqs, ObjectMapper objectMapper, String queueUrl) {
        this.sqs = Objects.requireNonNull(sqs, "sqs");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.queueUrl = Objects.requireNonNull(queueUrl, "queueUrl");
        if (queueUrl.isBlank()) throw new IllegalArgumentException("queueUrl must not be blank");
    }

    @Override
    public void publish(EventSearchProjectionAction action, String deduplicationId) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(deduplicationId, "deduplicationId");
        if (deduplicationId.isBlank()) throw new IllegalArgumentException("deduplicationId must not be blank");

        ProjectionEnvelope envelope = toEnvelope(action);
        try {
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(objectMapper.writeValueAsString(envelope))
                    .messageGroupId(envelope.eventId())
                    .messageDeduplicationId(deduplicationId)
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize event search projection", e);
        }
    }

    private static ProjectionEnvelope toEnvelope(EventSearchProjectionAction action) {
        if (action instanceof EventSearchProjection projection) {
            return new ProjectionEnvelope(
                    SCHEMA_VERSION,
                    "UPSERT",
                    projection.eventId(),
                    projection.name(),
                    projection.venue(),
                    projection.city(),
                    projection.startsAt().toEpochMilli(),
                    projection.category());
        }
        if (action instanceof DeleteEventSearchProjection deletion) {
            return new ProjectionEnvelope(
                    SCHEMA_VERSION,
                    "DELETE",
                    deletion.eventId(),
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        throw new IllegalArgumentException("unsupported event search projection action: " + action.getClass().getName());
    }

    record ProjectionEnvelope(
            int schemaVersion,
            String type,
            String eventId,
            String name,
            String venue,
            String city,
            Long startsAtEpochMillis,
            String category) {
    }
}
