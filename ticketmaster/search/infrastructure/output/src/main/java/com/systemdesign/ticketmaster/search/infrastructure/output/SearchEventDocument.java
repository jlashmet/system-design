package com.systemdesign.ticketmaster.search.infrastructure.output;

public record SearchEventDocument(
        String eventId,
        String name,
        String venue,
        String city,
        long startsAtEpochMillis,
        String category) {
}
