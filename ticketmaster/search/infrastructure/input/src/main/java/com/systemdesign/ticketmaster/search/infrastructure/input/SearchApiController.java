package com.systemdesign.ticketmaster.search.infrastructure.input;

import com.systemdesign.ticketmaster.search.api.SearchApi;
import com.systemdesign.ticketmaster.search.api.model.SearchEventResponse;
import com.systemdesign.ticketmaster.search.api.model.SearchPageResponse;
import com.systemdesign.ticketmaster.search.application.SearchEventsHandler;
import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import com.systemdesign.ticketmaster.search.domain.SearchPage;
import com.systemdesign.ticketmaster.search.domain.SearchQuery;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class SearchApiController implements SearchApi {
    private static final String SEARCH_CACHE_CONTROL =
            "public, max-age=15, stale-while-revalidate=30, stale-if-error=120";

    private final SearchEventsHandler searchEventsHandler;

    public SearchApiController(SearchEventsHandler searchEventsHandler) {
        this.searchEventsHandler = searchEventsHandler;
    }

    @Override
    public ResponseEntity<SearchPageResponse> searchEvents(
            String q,
            String city,
            OffsetDateTime startsAfter,
            OffsetDateTime startsBefore,
            String cursor,
            Integer limit) {
        SearchPage page = searchEventsHandler.handle(new SearchQuery(
                q,
                city,
                startsAfter == null ? null : startsAfter.toInstant(),
                startsBefore == null ? null : startsBefore.toInstant(),
                cursor,
                limit == null ? 20 : limit));

        SearchPageResponse response = new SearchPageResponse();
        response.setEvents(page.events().stream().map(SearchApiController::toResponse).toList());
        response.setNextCursor(page.nextCursor());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, SEARCH_CACHE_CONTROL)
                .body(response);
    }

    private static SearchEventResponse toResponse(SearchEvent event) {
        SearchEventResponse response = new SearchEventResponse();
        response.setEventId(event.eventId());
        response.setName(event.name());
        response.setVenue(event.venue());
        response.setCity(event.city());
        response.setStartsAt(event.startsAt().atOffset(ZoneOffset.UTC));
        response.setCategory(event.category());
        return response;
    }
}
