package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomDisabledException;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntryNotFoundException;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.util.Objects;

public final class CheckAdmissionHandler {
    private final WaitingRoomRepository repository;

    public CheckAdmissionHandler(WaitingRoomRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public AdmissionDecision handle(CheckAdmissionQuery query) {
        Objects.requireNonNull(query, "query");
        EventAdmission admission = repository.findAdmission(query.eventId())
                .orElseThrow(() -> new WaitingRoomDisabledException(query.eventId()));
        WaitingRoomEntry entry = repository.findEntry(query.eventId(), query.userId())
                .orElseThrow(() -> new WaitingRoomEntryNotFoundException(query.eventId(), query.userId()));
        return admission.admits(entry) ? AdmissionDecision.ADMITTED : AdmissionDecision.WAITING;
    }
}
