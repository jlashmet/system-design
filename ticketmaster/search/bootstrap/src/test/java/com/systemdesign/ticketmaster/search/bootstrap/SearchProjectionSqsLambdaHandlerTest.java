package com.systemdesign.ticketmaster.search.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.Test;

class SearchProjectionSqsLambdaHandlerTest {
    private final FakeSearchIndex index = new FakeSearchIndex();
    private final SearchProjectionSqsLambdaHandler handler = new SearchProjectionSqsLambdaHandler(
            new EventSearchProjectionConsumer(
                    new IndexSearchEventHandler(index),
                    new DeleteSearchEventHandler(index)),
            new ObjectMapper());

    @Test
    void consumesUpsertProjectionWithoutDependingOnEventsClasses() {
        handler.handleRequest(event("""
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
                """), null);

        assertThat(index.upserts).containsExactly(new SearchEvent(
                "event-1",
                "Taylor Swift",
                "SoFi Stadium",
                "Los Angeles",
                Instant.ofEpochMilli(1791601200000L),
                "CONCERT"));
        assertThat(index.deletes).isEmpty();
    }

    @Test
    void consumesDeleteProjection() {
        handler.handleRequest(event("""
                {"schemaVersion":1,"type":"DELETE","eventId":"event-1"}
                """), null);

        assertThat(index.deletes).containsExactly("event-1");
        assertThat(index.upserts).isEmpty();
    }

    @Test
    void rejectsUnsupportedSchemaVersionSoTheBatchCanRetryOrDeadLetter() {
        assertThatThrownBy(() -> handler.handleRequest(event("""
                {"schemaVersion":2,"type":"DELETE","eventId":"event-1"}
                """), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported event search projection schema version");
    }

    @Test
    void rejectsIncompleteUpsertInsteadOfIndexingPartialDocument() {
        assertThatThrownBy(() -> handler.handleRequest(event("""
                {"schemaVersion":1,"type":"UPSERT","eventId":"event-1","name":"Taylor Swift"}
                """), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("venue must not be blank");

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
