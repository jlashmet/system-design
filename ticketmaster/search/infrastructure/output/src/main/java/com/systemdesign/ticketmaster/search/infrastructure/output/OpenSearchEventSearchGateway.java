package com.systemdesign.ticketmaster.search.infrastructure.output;

import com.systemdesign.ticketmaster.search.domain.EventSearchGateway;
import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import com.systemdesign.ticketmaster.search.domain.SearchPage;
import com.systemdesign.ticketmaster.search.domain.SearchQuery;
import com.systemdesign.ticketmaster.search.domain.SearchUnavailableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.RangeQuery;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;

public final class OpenSearchEventSearchGateway implements EventSearchGateway {
    private final OpenSearchClient client;
    private final String indexName;

    public OpenSearchEventSearchGateway(OpenSearchClient client, String indexName) {
        this.client = Objects.requireNonNull(client, "client");
        this.indexName = Objects.requireNonNull(indexName, "indexName");
        if (indexName.isBlank()) throw new IllegalArgumentException("indexName must not be blank");
    }

    @Override
    public SearchPage search(SearchQuery query) {
        Objects.requireNonNull(query, "query");
        SearchRequest.Builder request = new SearchRequest.Builder()
                .index(indexName)
                .size(query.limit() + 1)
                .query(toQuery(query))
                .sort(sort -> sort.field(field -> field.field("startsAtEpochMillis").order(SortOrder.Asc)))
                .sort(sort -> sort.field(field -> field.field("eventId").order(SortOrder.Asc)));

        if (!query.cursor().isBlank()) {
            SearchCursor cursor = decodeCursor(query.cursor());
            request.searchAfter(List.of(
                    FieldValue.of(cursor.startsAtEpochMillis()),
                    FieldValue.of(cursor.eventId())));
        }

        try {
            SearchResponse<SearchEventDocument> response = client.search(request.build(), SearchEventDocument.class);
            List<Hit<SearchEventDocument>> hits = response.hits().hits();
            boolean hasNextPage = hits.size() > query.limit();
            List<SearchEventDocument> documents = hits.stream()
                    .limit(query.limit())
                    .map(Hit::source)
                    .map(source -> Objects.requireNonNull(source, "OpenSearch hit is missing _source"))
                    .toList();
            List<SearchEvent> events = documents.stream().map(OpenSearchEventSearchGateway::toDomain).toList();
            String nextCursor = hasNextPage && !documents.isEmpty()
                    ? encodeCursor(documents.get(documents.size() - 1))
                    : "";
            return new SearchPage(events, nextCursor);
        } catch (IOException | RuntimeException backendFailure) {
            throw new SearchUnavailableException("event search is temporarily unavailable", backendFailure);
        }
    }

    private static Query toQuery(SearchQuery query) {
        List<Query> must = new ArrayList<>();
        List<Query> filter = new ArrayList<>();

        if (!query.text().isBlank()) {
            must.add(Query.of(q -> q.multiMatch(m -> m
                    .fields("name", "venue", "category")
                    .query(query.text()))));
        }
        if (!query.city().isBlank()) {
            filter.add(Query.of(q -> q.term(t -> t
                    .field("city")
                    .value(FieldValue.of(query.city())))));
        }
        if (query.startsAfter() != null || query.startsBefore() != null) {
            RangeQuery.Builder range = new RangeQuery.Builder().field("startsAtEpochMillis");
            if (query.startsAfter() != null) {
                range.gte(JsonData.of(query.startsAfter().toEpochMilli()));
            }
            if (query.startsBefore() != null) {
                range.lte(JsonData.of(query.startsBefore().toEpochMilli()));
            }
            filter.add(new Query(range.build()));
        }

        if (must.isEmpty() && filter.isEmpty()) {
            return Query.of(q -> q.matchAll(matchAll -> matchAll));
        }
        return Query.of(q -> q.bool(bool -> bool.must(must).filter(filter)));
    }

    private static SearchEvent toDomain(SearchEventDocument document) {
        return new SearchEvent(
                document.eventId(),
                document.name(),
                document.venue(),
                document.city(),
                Instant.ofEpochMilli(document.startsAtEpochMillis()),
                document.category());
    }

    private static String encodeCursor(SearchEventDocument document) {
        String plain = document.startsAtEpochMillis() + "\n" + document.eventId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private static SearchCursor decodeCursor(String encoded) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = plain.split("\n", 2);
            if (parts.length != 2 || parts[1].isBlank()) throw new IllegalArgumentException("invalid cursor");
            return new SearchCursor(Long.parseLong(parts[0]), parts[1]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid search cursor", e);
        }
    }

    private record SearchCursor(long startsAtEpochMillis, String eventId) {
    }
}
