package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.util.Objects;

public final class CheckAdmissionHandler {
    private final WaitingRoomRepository repository;

    public CheckAdmissionHandler(WaitingRoomRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public AdmissionDecision handle(CheckAdmissionQuery query) {
        Objects.requireNonNull(query, "query");
        WaitingRoomEntry entry = repository.findEntry(query.eventId(), query.userId())
                .orElseThrow(() -> new IllegalArgumentException("user has not joined the waiting room"));
        return repository.findAdmission(query.eventId())
                .filter(admission -> admission.admits(entry))
                .map(ignored -> AdmissionDecision.ADMITTED)
                .orElse(AdmissionDecision.WAITING);
    }
}
