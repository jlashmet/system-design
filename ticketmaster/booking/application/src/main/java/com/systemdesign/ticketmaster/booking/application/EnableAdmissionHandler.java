package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.AdmissionWatermarkRegressionException;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.time.Instant;
import java.util.Objects;

public final class EnableAdmissionHandler {
    private static final Instant CLOSED_WATERMARK = Instant.EPOCH;

    private final WaitingRoomRepository waitingRoomRepository;

    public EnableAdmissionHandler(WaitingRoomRepository waitingRoomRepository) {
        this.waitingRoomRepository = Objects.requireNonNull(waitingRoomRepository, "waitingRoomRepository");
    }

    public EventAdmission handle(EnableAdmissionCommand command) {
        Objects.requireNonNull(command, "command");
        return waitingRoomRepository.findAdmission(command.eventId())
                .orElseGet(() -> createClosedAdmission(command));
    }

    private EventAdmission createClosedAdmission(EnableAdmissionCommand command) {
        try {
            return waitingRoomRepository.advanceAdmission(
                    new EventAdmission(command.eventId(), CLOSED_WATERMARK));
        } catch (AdmissionWatermarkRegressionException concurrentAdvance) {
            return waitingRoomRepository.findAdmission(command.eventId())
                    .orElseThrow(() -> new IllegalStateException(
                            "admission watermark disappeared after concurrent initialization",
                            concurrentAdvance));
        }
    }
}
