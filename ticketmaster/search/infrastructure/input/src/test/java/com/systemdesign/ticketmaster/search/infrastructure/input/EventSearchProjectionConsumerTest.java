package com.systemdesign.ticketmaster.search.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.search.application.IndexSearchEventHandler;
import com.systemdesign.ticketmaster.search.domain.EventSearchIndex;
import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventSearchProjectionConsumerTest {

    @Test
    void convertsProjectionMessageIntoSearchEvent() {
        RecordingIndex index = new RecordingIndex();
        EventSearchProjectionConsumer consumer = new EventSearchProjectionConsumer(new IndexSearchEventHandler(index));

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
    }

    private static final class RecordingIndex implements EventSearchIndex {
        private SearchEvent indexed;

        @Override
        public void upsert(SearchEvent event) {
            indexed = event;
        }
    }
}
