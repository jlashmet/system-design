package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import java.util.Objects;

public final class ProjectSeatMapHandler {
    private final SeatMapRepository repository;

    public ProjectSeatMapHandler(SeatMapRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public void handle(SeatMapSeat seat) {
        repository.upsert(Objects.requireNonNull(seat, "seat"));
    }
}
