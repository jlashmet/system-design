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
        // Start one millisecond behind the service clock so a join stamped in this same millisecond
        // is still queued. initializeAdmission must never advance an existing watermark because
        // another replica may already be serving the queue.
        return waitingRoomRepository.initializeAdmission(
                new EventAdmission(command.eventId(), clock.instant().minusMillis(1)));
    }
}
