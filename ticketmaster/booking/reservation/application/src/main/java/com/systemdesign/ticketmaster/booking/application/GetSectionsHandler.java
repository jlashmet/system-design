package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import java.util.List;
import java.util.Objects;

public final class GetSectionsHandler {
    private final SeatMapRepository repository;

    public GetSectionsHandler(SeatMapRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public List<SectionId> handle(GetSectionsQuery query) {
        Objects.requireNonNull(query, "query");
        return repository.findSections(query.eventId());
    }
}
