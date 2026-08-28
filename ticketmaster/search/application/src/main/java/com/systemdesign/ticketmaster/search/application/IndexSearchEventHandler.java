package com.systemdesign.ticketmaster.search.application;

import com.systemdesign.ticketmaster.search.domain.EventSearchIndex;
import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import java.util.Objects;

public final class IndexSearchEventHandler {
    private final EventSearchIndex index;

    public IndexSearchEventHandler(EventSearchIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    public void handle(SearchEvent event) {
        index.upsert(Objects.requireNonNull(event, "event"));
    }
}
