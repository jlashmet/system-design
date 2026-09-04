package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import java.util.List;
import java.util.Objects;

public final class GetSectionSeatsHandler {
    private final SeatMapRepository repository;

    public GetSectionSeatsHandler(SeatMapRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public List<SeatMapSeat> handle(GetSectionSeatsQuery query) {
        Objects.requireNonNull(query, "query");
        return repository.findSection(query.eventId(), query.sectionId());
    }
}
