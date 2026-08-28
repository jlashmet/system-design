package com.systemdesign.ticketmaster.search.bootstrap;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.ticketmaster.search.application.DeleteSearchEventHandler;
import com.systemdesign.ticketmaster.search.application.IndexSearchEventHandler;
import com.systemdesign.ticketmaster.search.domain.EventSearchIndex;
import com.systemdesign.ticketmaster.search.infrastructure.input.EventSearchDeletionMessage;
import com.systemdesign.ticketmaster.search.infrastructure.input.EventSearchProjectionConsumer;
import com.systemdesign.ticketmaster.search.infrastructure.input.EventSearchProjectionMessage;
import com.systemdesign.ticketmaster.search.infrastructure.output.OpenSearchEventSearchIndex;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.aws.AwsSdk2Transport;
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;

/**
 * SQS FIFO Lambda consumer for the cross-context Event search projection.
 *
 * <p>The producer groups messages by event ID, so per-event changes are delivered in order. This
 * handler deliberately fails the whole Lambda batch if parsing or indexing fails. Search projection
 * writes are idempotent by event ID, making whole-batch retry safe and keeping checkpoint logic out
 * of the application.</p>
 */
public final class SearchProjectionSqsLambdaHandler implements RequestHandler<SQSEvent, Void> {
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final String ENDPOINT_ENV = "TICKETMASTER_SEARCH_ENDPOINT";
    private static final String INDEX_ENV = "TICKETMASTER_SEARCH_INDEX_NAME";
    private static final String SERVICE_ENV = "TICKETMASTER_SEARCH_SIGNING_SERVICE";

    private final EventSearchProjectionConsumer consumer;
    private final ObjectMapper objectMapper;

    public SearchProjectionSqsLambdaHandler() {
        this(defaultConsumer(), new ObjectMapper());
    }

    SearchProjectionSqsLambdaHandler(EventSearchProjectionConsumer consumer, ObjectMapper objectMapper) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        Objects.requireNonNull(event, "event");
        if (event.getRecords() == null) return null;
        for (SQSEvent.SQSMessage record : event.getRecords()) {
            if (record == null) continue;
            accept(record.getBody());
        }
        return null;
    }

    private void accept(String body) {
        ProjectionEnvelope envelope;
        try {
            envelope = objectMapper.readValue(requireNonBlank(body, "SQS message body"), ProjectionEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid event search projection JSON", e);
        }
        if (envelope.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported event search projection schema version: "
                    + envelope.schemaVersion());
        }

        String type = requireNonBlank(envelope.type(), "type");
        String eventId = requireNonBlank(envelope.eventId(), "eventId");
        switch (type) {
            case "UPSERT" -> consumer.accept(new EventSearchProjectionMessage(
                    eventId,
                    requireNonBlank(envelope.name(), "name"),
                    requireNonBlank(envelope.venue(), "venue"),
                    requireNonBlank(envelope.city(), "city"),
                    requireNonNull(envelope.startsAtEpochMillis(), "startsAtEpochMillis"),
                    requireNonBlank(envelope.category(), "category")));
            case "DELETE" -> consumer.accept(new EventSearchDeletionMessage(eventId));
            default -> throw new IllegalArgumentException("unsupported event search projection type: " + type);
        }
    }

    private static EventSearchProjectionConsumer defaultConsumer() {
        String endpoint = requireEnv(ENDPOINT_ENV);
        String indexName = System.getenv().getOrDefault(INDEX_ENV, "events");
        String service = System.getenv().getOrDefault(SERVICE_ENV, "es");
        String regionName = requireEnv("AWS_REGION");

        URI uri = endpoint.contains("://") ? URI.create(endpoint) : URI.create("https://" + endpoint);
        String host = requireNonBlank(uri.getHost(), "OpenSearch endpoint host");
        SdkHttpClient httpClient = Apache5HttpClient.builder()
                .connectionTimeout(Duration.ofMillis(200))
                .socketTimeout(Duration.ofMillis(450))
                .build();
        OpenSearchClient client = new OpenSearchClient(new AwsSdk2Transport(
                httpClient,
                host,
                service,
                Region.of(regionName),
                AwsSdk2TransportOptions.builder().build()));
        EventSearchIndex index = new OpenSearchEventSearchIndex(client, indexName);
        return new EventSearchProjectionConsumer(
                new IndexSearchEventHandler(index),
                new DeleteSearchEventHandler(index));
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured");
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static long requireNonNull(Long value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
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
