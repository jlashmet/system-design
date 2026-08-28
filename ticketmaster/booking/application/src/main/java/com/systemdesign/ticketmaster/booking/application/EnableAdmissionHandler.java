package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.time.Clock;
import java.util.Objects;

public final class EnableAdmissionHandler {
    private final WaitingRoomRepository waitingRoomRepository;
    private final Clock clock;

    public EnableAdmissionHandler(WaitingRoomRepository waitingRoomRepository, Clock clock) {
        this.waitingRoomRepository = Objects.requireNonNull(waitingRoomRepository, "waitingRoomRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EventAdmission handle(EnableAdmissionCommand command) {
        Objects.requireNonNull(command, "command");
        // A configured hot event starts closed at the current time. initializeAdmission must not
        // advance an existing watermark: another replica may already be serving the queue.
        return waitingRoomRepository.initializeAdmission(
                new EventAdmission(command.eventId(), clock.instant()));
    }
}
