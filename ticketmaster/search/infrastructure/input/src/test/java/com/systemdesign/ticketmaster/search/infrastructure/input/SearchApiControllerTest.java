package com.systemdesign.ticketmaster.search.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.search.api.model.SearchPageResponse;
import com.systemdesign.ticketmaster.search.application.SearchEventsHandler;
import com.systemdesign.ticketmaster.search.domain.EventSearchGateway;
import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import com.systemdesign.ticketmaster.search.domain.SearchPage;
import com.systemdesign.ticketmaster.search.domain.SearchQuery;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class SearchApiControllerTest {

    @Test
    void mapsGeneratedParametersToDomainQueryAndBackWithSharedCachePolicy() {
        CapturingSearchGateway gateway = new CapturingSearchGateway();
        SearchApiController controller = new SearchApiController(new SearchEventsHandler(gateway));
        OffsetDateTime after = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime before = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        ResponseEntity<SearchPageResponse> response = controller.searchEvents(
                "Taylor", "Los Angeles", after, before, "cursor-1", 25);

        assertThat(gateway.query).isEqualTo(new SearchQuery(
                "Taylor", "Los Angeles", after.toInstant(), before.toInstant(), "cursor-1", 25));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("public, max-age=15, stale-while-revalidate=30, stale-if-error=120");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getNextCursor()).isEqualTo("cursor-2");
        assertThat(response.getBody().getEvents()).singleElement().satisfies(event -> {
            assertThat(event.getEventId()).isEqualTo("event-1");
            assertThat(event.getName()).isEqualTo("Taylor Swift");
            assertThat(event.getStartsAt().toInstant()).isEqualTo(Instant.parse("2026-10-10T03:00:00Z"));
        });
    }

    private static final class CapturingSearchGateway implements EventSearchGateway {
        private SearchQuery query;

        @Override
        public SearchPage search(SearchQuery query) {
            this.query = query;
            return new SearchPage(List.of(new SearchEvent(
                    "event-1",
                    "Taylor Swift",
                    "SoFi Stadium",
                    "Los Angeles",
                    Instant.parse("2026-10-10T03:00:00Z"),
                    "CONCERT")), "cursor-2");
        }
    }
}
