package com.systemdesign.ticketmaster.search.domain;

import java.util.List;

public record SearchPage(List<SearchEvent> events, String nextCursor) {
    public SearchPage {
        events = List.copyOf(events);
        nextCursor = nextCursor == null ? "" : nextCursor;
    }
}
