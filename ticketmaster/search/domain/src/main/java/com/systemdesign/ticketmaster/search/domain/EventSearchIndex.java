package com.systemdesign.ticketmaster.search.domain;

public interface EventSearchIndex {
    void upsert(SearchEvent event);

    void delete(String eventId);
}
