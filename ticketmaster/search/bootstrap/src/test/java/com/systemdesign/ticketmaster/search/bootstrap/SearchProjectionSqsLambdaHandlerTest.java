package com.systemdesign.ticketmaster.search.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
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
    private SQSEvent input;
    private SQSBatchResponse response;

    @BeforeEach
    void reset() {
        index = new FakeSearchIndex();
        handler = new SearchProjectionSqsLambdaHandler(
                new EventSearchProjectionConsumer(
                        new IndexSearchEventHandler(index),
                        new DeleteSearchEventHandler(index)),
                new ObjectMapper());
        input = null;
        response = null;
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
        thenExpectUpsertAndNoFailures();
    }

    @Test
    void consumesDeleteProjection() {
        givenProjection("""
                {"schemaVersion":1,"type":"DELETE","eventId":"event-1"}
                """);
        whenProjectionIsConsumed();
        thenExpectDeleteAndNoFailures();
    }

    @Test
    void reportsUnsupportedSchemaVersionForRetryOrDeadLetter() {
        givenProjection("""
                {"schemaVersion":2,"type":"DELETE","eventId":"event-1"}
                """);
        whenProjectionIsConsumed();
        thenExpectFailures("message-1");
    }

    @Test
    void reportsIncompleteUpsertWithoutIndexingPartialDocument() {
        givenProjection("""
                {"schemaVersion":1,"type":"UPSERT","eventId":"event-1","name":"Taylor Swift"}
                """);
        whenProjectionIsConsumed();
        thenExpectFailureWithoutUpsert("message-1");
    }

    @Test
    void acknowledgesSuccessfulPrefixAndRetriesFailedAndUnprocessedFifoSuffix() {
        givenBatchWithSuccessfulPrefixAndMalformedSecondRecord();
        whenProjectionIsConsumed();
        thenExpectOnlyPrefixAppliedAndSuffixRetried();
    }

    @Test
    void reportsIndexFailureAndLeavesLaterFifoRecordUnprocessed() {
        givenBatchWithIndexFailureInSecondRecord();
        whenProjectionIsConsumed();
        thenExpectIndexFailureAndLaterRecordRetried();
    }

    private void givenProjection(String body) {
        input = event(message("message-1", body));
    }

    private void givenBatchWithSuccessfulPrefixAndMalformedSecondRecord() {
        input = event(
                message("message-1", upsert("event-1")),
                message("message-2", """
                        {"schemaVersion":2,"type":"DELETE","eventId":"event-2"}
                        """),
                message("message-3", """
                        {"schemaVersion":1,"type":"DELETE","eventId":"event-3"}
                        """));
    }

    private void givenBatchWithIndexFailureInSecondRecord() {
        index.failUpsertEventId = "event-2";
        input = event(
                message("message-1", upsert("event-1")),
                message("message-2", upsert("event-2")),
                message("message-3", upsert("event-3")));
    }

    private void whenProjectionIsConsumed() {
        response = handler.handleRequest(input, null);
    }

    private void thenExpectUpsertAndNoFailures() {
        assertThat(index.upserts).containsExactly(searchEvent("event-1"));
        assertThat(index.deletes).isEmpty();
        assertThat(failureIds()).isEmpty();
    }

    private void thenExpectDeleteAndNoFailures() {
        assertThat(index.deletes).containsExactly("event-1");
        assertThat(index.upserts).isEmpty();
        assertThat(failureIds()).isEmpty();
    }

    private void thenExpectFailures(String... messageIds) {
        assertThat(failureIds()).containsExactly(messageIds);
    }

    private void thenExpectFailureWithoutUpsert(String messageId) {
        assertThat(failureIds()).containsExactly(messageId);
        assertThat(index.upserts).isEmpty();
    }

    private void thenExpectOnlyPrefixAppliedAndSuffixRetried() {
        assertThat(index.upserts).containsExactly(searchEvent("event-1"));
        assertThat(index.deletes).isEmpty();
        assertThat(failureIds()).containsExactly("message-2", "message-3");
    }

    private void thenExpectIndexFailureAndLaterRecordRetried() {
        assertThat(index.upserts).containsExactly(searchEvent("event-1"));
        assertThat(failureIds()).containsExactly("message-2", "message-3");
    }

    private List<String> failureIds() {
        return response.getBatchItemFailures().stream()
                .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .toList();
    }

    private static SQSEvent event(SQSEvent.SQSMessage... messages) {
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(messages));
        return event;
    }

    private static SQSEvent.SQSMessage message(String messageId, String body) {
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId(messageId);
        message.setBody(body);
        return message;
    }

    private static String upsert(String eventId) {
        return """
                {
                  "schemaVersion": 1,
                  "type": "UPSERT",
                  "eventId": "%s",
                  "name": "Taylor Swift",
                  "venue": "SoFi Stadium",
                  "city": "Los Angeles",
                  "startsAtEpochMillis": 1791601200000,
                  "category": "CONCERT"
                }
                """.formatted(eventId);
    }

    private static SearchEvent searchEvent(String eventId) {
        return new SearchEvent(
                eventId,
                "Taylor Swift",
                "SoFi Stadium",
                "Los Angeles",
                Instant.ofEpochMilli(1791601200000L),
                "CONCERT");
    }

    private static final class FakeSearchIndex implements EventSearchIndex {
        private final List<SearchEvent> upserts = new ArrayList<>();
        private final List<String> deletes = new ArrayList<>();
        private String failUpsertEventId;

        @Override
        public void upsert(SearchEvent event) {
            if (event.eventId().equals(failUpsertEventId)) {
                throw new IllegalStateException("simulated OpenSearch failure");
            }
            upserts.add(event);
        }

        @Override
        public void delete(String eventId) {
            deletes.add(eventId);
        }
    }
}
