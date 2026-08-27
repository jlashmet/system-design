package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.util.Objects;

public final class AdvanceAdmissionHandler {
    private final WaitingRoomRepository repository;

    public AdvanceAdmissionHandler(WaitingRoomRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public EventAdmission handle(AdvanceAdmissionCommand command) {
        Objects.requireNonNull(command, "command");
        return repository.advanceAdmission(new EventAdmission(command.eventId(), command.admittedThrough()));
    }
}
