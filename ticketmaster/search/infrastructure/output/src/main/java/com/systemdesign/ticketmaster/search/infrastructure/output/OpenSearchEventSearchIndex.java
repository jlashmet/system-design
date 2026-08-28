package com.systemdesign.ticketmaster.search.infrastructure.output;

import com.systemdesign.ticketmaster.search.domain.EventSearchIndex;
import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import java.io.IOException;
import java.util.Objects;
import org.opensearch.client.opensearch.OpenSearchClient;

public final class OpenSearchEventSearchIndex implements EventSearchIndex {
    private final OpenSearchClient client;
    private final String indexName;

    public OpenSearchEventSearchIndex(OpenSearchClient client, String indexName) {
        this.client = Objects.requireNonNull(client, "client");
        this.indexName = Objects.requireNonNull(indexName, "indexName");
        if (indexName.isBlank()) throw new IllegalArgumentException("indexName must not be blank");
    }

    @Override
    public void upsert(SearchEvent event) {
        Objects.requireNonNull(event, "event");
        SearchEventDocument document = new SearchEventDocument(
                event.eventId(),
                event.name(),
                event.venue(),
                event.city(),
                event.startsAt().toEpochMilli(),
                event.category());
        try {
            client.index(request -> request
                    .index(indexName)
                    .id(event.eventId())
                    .document(document));
        } catch (IOException e) {
            throw new OpenSearchIndexException("event indexing failed", e);
        }
    }

    @Override
    public void delete(String eventId) {
        Objects.requireNonNull(eventId, "eventId");
        if (eventId.isBlank()) throw new IllegalArgumentException("eventId must not be blank");
        try {
            client.delete(request -> request
                    .index(indexName)
                    .id(eventId));
        } catch (IOException e) {
            throw new OpenSearchIndexException("event deletion failed", e);
        }
    }
}
