package com.systemdesign.ticketmaster.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.search.domain.EventSearchIndex;
import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IndexSearchEventHandlerTest {

    @Test
    void indexesTheSearchEventThroughThePort() {
        RecordingIndex index = new RecordingIndex();
        IndexSearchEventHandler handler = new IndexSearchEventHandler(index);
        SearchEvent event = new SearchEvent(
                "event-42",
                "The National",
                "Hollywood Bowl",
                "Los Angeles",
                Instant.parse("2026-10-20T03:00:00Z"),
                "CONCERT");

        handler.handle(event);

        assertThat(index.indexed).isEqualTo(event);
    }

    private static final class RecordingIndex implements EventSearchIndex {
        private SearchEvent indexed;

        @Override
        public void upsert(SearchEvent event) {
            indexed = event;
        }

        @Override
        public void delete(String eventId) {
        }
    }
}
