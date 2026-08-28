package com.systemdesign.ticketmaster.search.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.search.application.DeleteSearchEventHandler;
import com.systemdesign.ticketmaster.search.application.IndexSearchEventHandler;
import com.systemdesign.ticketmaster.search.domain.EventSearchIndex;
import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventSearchProjectionConsumerTest {

    @Test
    void convertsProjectionMessageIntoSearchEvent() {
        RecordingIndex index = new RecordingIndex();
        EventSearchProjectionConsumer consumer = consumer(index);

        consumer.accept(new EventSearchProjectionMessage(
                "event-42",
                "The National",
                "Hollywood Bowl",
                "Los Angeles",
                Instant.parse("2026-10-20T03:00:00Z").toEpochMilli(),
                "CONCERT"));

        assertThat(index.indexed).isEqualTo(new SearchEvent(
                "event-42",
                "The National",
                "Hollywood Bowl",
                "Los Angeles",
                Instant.parse("2026-10-20T03:00:00Z"),
                "CONCERT"));
        assertThat(index.deletedEventId).isNull();
    }

    @Test
    void convertsDeletionMessageIntoIndexDelete() {
        RecordingIndex index = new RecordingIndex();
        EventSearchProjectionConsumer consumer = consumer(index);

        consumer.accept(new EventSearchDeletionMessage("event-42"));

        assertThat(index.deletedEventId).isEqualTo("event-42");
        assertThat(index.indexed).isNull();
    }

    private static EventSearchProjectionConsumer consumer(RecordingIndex index) {
        return new EventSearchProjectionConsumer(
                new IndexSearchEventHandler(index),
                new DeleteSearchEventHandler(index));
    }

    private static final class RecordingIndex implements EventSearchIndex {
        private SearchEvent indexed;
        private String deletedEventId;

        @Override
        public void upsert(SearchEvent event) {
            indexed = event;
        }

        @Override
        public void delete(String eventId) {
            deletedEventId = eventId;
        }
    }
}
