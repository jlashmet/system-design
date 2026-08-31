package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.WaitingRoomDisabledException;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.time.Clock;
import java.util.Objects;

public final class JoinWaitingRoomHandler {
    private final WaitingRoomRepository repository;
    private final Clock clock;

    public JoinWaitingRoomHandler(WaitingRoomRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WaitingRoomEntry handle(JoinWaitingRoomCommand command) {
        Objects.requireNonNull(command, "command");
        if (repository.findAdmission(command.eventId()).isEmpty()) {
            throw new WaitingRoomDisabledException(command.eventId());
        }
        return repository.join(new WaitingRoomEntry(command.eventId(), command.userId(), clock.instant()));
    }
}
