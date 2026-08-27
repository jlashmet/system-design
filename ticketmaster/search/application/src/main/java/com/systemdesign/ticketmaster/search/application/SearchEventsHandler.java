package com.systemdesign.ticketmaster.search.application;

import com.systemdesign.ticketmaster.search.domain.EventSearchGateway;
import com.systemdesign.ticketmaster.search.domain.SearchPage;
import com.systemdesign.ticketmaster.search.domain.SearchQuery;
import java.util.Objects;

public final class SearchEventsHandler {
    private final EventSearchGateway eventSearchGateway;

    public SearchEventsHandler(EventSearchGateway eventSearchGateway) {
        this.eventSearchGateway = Objects.requireNonNull(eventSearchGateway, "eventSearchGateway");
    }

    public SearchPage handle(SearchQuery query) {
        return eventSearchGateway.search(Objects.requireNonNull(query, "query"));
    }
}
