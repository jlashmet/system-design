package com.systemdesign.ticketmaster.search.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.ticketmaster.search.application.DeleteSearchEventHandler;
import com.systemdesign.ticketmaster.search.application.IndexSearchEventHandler;
import com.systemdesign.ticketmaster.search.domain.EventSearchIndex;
import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import com.systemdesign.ticketmaster.search.infrastructure.input.EventSearchProjectionConsumer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchProjectionSqsLambdaHandlerTest {
    private FakeSearchIndex index;
    private SearchProjectionSqsLambdaHandler handler;
    private Throwable thrown;

    @BeforeEach
    void reset() {
        index = new FakeSearchIndex();
        handler = new SearchProjectionSqsLambdaHandler(
                new EventSearchProjectionConsumer(
                        new IndexSearchEventHandler(index),
                        new DeleteSearchEventHandler(index)),
                new ObjectMapper());
        thrown = null;
    }

    @Test
    void consumesUpsertProjectionWithoutDependingOnEventsClasses() {
        givenProjection("""
                {
                  "schemaVersion": 1,
                  "type": "UPSERT",
                  "eventId": "event-1",
                  "name": "Taylor Swift",
                  "venue": "SoFi Stadium",
                  "city": "Los Angeles",
                  "startsAtEpochMillis": 1791601200000,
                  "category": "CONCERT"
                }
                """);
        whenProjectionIsConsumed();
        thenExpectUpsert();
    }

    @Test
    void consumesDeleteProjection() {
        givenProjection("""
                {"schemaVersion":1,"type":"DELETE","eventId":"event-1"}
                """);
        whenProjectionIsConsumed();
        thenExpectDelete();
    }

    @Test
    void rejectsUnsupportedSchemaVersionSoTheBatchCanRetryOrDeadLetter() {
        givenProjection("""
                {"schemaVersion":2,"type":"DELETE","eventId":"event-1"}
                """);
        whenProjectionIsConsumed();
        thenExpectRejected("unsupported event search projection schema version");
    }

    @Test
    void rejectsIncompleteUpsertInsteadOfIndexingPartialDocument() {
        givenProjection("""
                {"schemaVersion":1,"type":"UPSERT","eventId":"event-1","name":"Taylor Swift"}
                """);
        whenProjectionIsConsumed();
        thenExpectRejectedWithoutUpsert("venue must not be blank");
    }

    private String projectionBody;

    private void givenProjection(String body) {
        projectionBody = body;
        thrown = null;
    }

    private void whenProjectionIsConsumed() {
        try {
            handler.handleRequest(event(projectionBody), null);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectUpsert() {
        assertThat(thrown).isNull();
        assertThat(index.upserts).containsExactly(new SearchEvent(
                "event-1",
                "Taylor Swift",
                "SoFi Stadium",
                "Los Angeles",
                Instant.ofEpochMilli(1791601200000L),
                "CONCERT"));
        assertThat(index.deletes).isEmpty();
    }

    private void thenExpectDelete() {
        assertThat(thrown).isNull();
        assertThat(index.deletes).containsExactly("event-1");
        assertThat(index.upserts).isEmpty();
    }

    private void thenExpectRejected(String message) {
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private void thenExpectRejectedWithoutUpsert(String message) {
        thenExpectRejected(message);
        assertThat(index.upserts).isEmpty();
    }

    private static SQSEvent event(String body) {
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setBody(body);
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(message));
        return event;
    }

    private static final class FakeSearchIndex implements EventSearchIndex {
        private final List<SearchEvent> upserts = new ArrayList<>();
        private final List<String> deletes = new ArrayList<>();

        @Override
        public void upsert(SearchEvent event) {
            upserts.add(event);
        }

        @Override
        public void delete(String eventId) {
            deletes.add(eventId);
        }
    }
}
