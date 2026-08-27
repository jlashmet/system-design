package com.systemdesign.ticketmaster.search.domain;

import java.time.Instant;

public record SearchQuery(
        String text,
        String city,
        Instant startsAfter,
        Instant startsBefore,
        String cursor,
        int limit) {

    public SearchQuery {
        text = text == null ? "" : text.trim();
        city = city == null ? "" : city.trim();
        cursor = cursor == null ? "" : cursor;
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (startsAfter != null && startsBefore != null && startsAfter.isAfter(startsBefore)) {
            throw new IllegalArgumentException("startsAfter must not be after startsBefore");
        }
    }
}
