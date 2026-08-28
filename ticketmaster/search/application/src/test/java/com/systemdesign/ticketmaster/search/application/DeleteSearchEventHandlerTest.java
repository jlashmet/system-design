package com.systemdesign.ticketmaster.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.search.domain.EventSearchIndex;
import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import org.junit.jupiter.api.Test;

class DeleteSearchEventHandlerTest {

    @Test
    void deletesTheEventThroughTheIndexPort() {
        RecordingIndex index = new RecordingIndex();
        DeleteSearchEventHandler handler = new DeleteSearchEventHandler(index);

        handler.handle("event-42");

        assertThat(index.deletedEventId).isEqualTo("event-42");
    }

    private static final class RecordingIndex implements EventSearchIndex {
        private String deletedEventId;

        @Override
        public void upsert(SearchEvent event) {
        }

        @Override
        public void delete(String eventId) {
            deletedEventId = eventId;
        }
    }
}
