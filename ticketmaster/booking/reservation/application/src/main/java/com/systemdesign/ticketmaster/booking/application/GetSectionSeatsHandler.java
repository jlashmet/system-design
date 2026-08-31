package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class GetSectionSeatsHandler {
    private final SeatMapRepository repository;
    private final Clock clock;

    public GetSectionSeatsHandler(SeatMapRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<SeatMapSeat> handle(GetSectionSeatsQuery query) {
        Objects.requireNonNull(query, "query");
        Instant now = clock.instant();
        return repository.findSection(query.eventId(), query.sectionId()).stream()
                .map(seat -> seat.forDisplayAt(now))
                .toList();
    }
}
