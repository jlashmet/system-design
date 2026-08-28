package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.AdmissionWatermarkRegressionException;
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
        return waitingRoomRepository.findAdmission(command.eventId())
                .orElseGet(() -> createClosedAdmission(command));
    }

    private EventAdmission createClosedAdmission(EnableAdmissionCommand command) {
        // The service initializes configured hot events before accepting traffic. Starting at 'now'
        // means subsequent joins are queued immediately, while regulation can advance from a live
        // timestamp rather than spending years catching up from an arbitrary historical epoch.
        EventAdmission initial = new EventAdmission(command.eventId(), clock.instant());
        try {
            return waitingRoomRepository.advanceAdmission(initial);
        } catch (AdmissionWatermarkRegressionException concurrentAdvance) {
            return waitingRoomRepository.findAdmission(command.eventId())
                    .orElseThrow(() -> new IllegalStateException(
                            "admission watermark disappeared after concurrent initialization",
                            concurrentAdvance));
        }
    }
}
