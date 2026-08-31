package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.AdmissionCapacity;
import com.systemdesign.ticketmaster.booking.domain.AdmissionHealthGateway;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class RegulateAdmissionHandler {
    private final WaitingRoomRepository waitingRoomRepository;
    private final AdmissionHealthGateway admissionHealthGateway;
    private final Clock clock;
    private final Duration healthyAdvance;
    private final Duration constrainedAdvance;

    public RegulateAdmissionHandler(
            WaitingRoomRepository waitingRoomRepository,
            AdmissionHealthGateway admissionHealthGateway,
            Clock clock,
            Duration healthyAdvance,
            Duration constrainedAdvance) {
        this.waitingRoomRepository = Objects.requireNonNull(waitingRoomRepository, "waitingRoomRepository");
        this.admissionHealthGateway = Objects.requireNonNull(admissionHealthGateway, "admissionHealthGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.healthyAdvance = requirePositive(healthyAdvance, "healthyAdvance");
        this.constrainedAdvance = requirePositive(constrainedAdvance, "constrainedAdvance");
        if (constrainedAdvance.compareTo(healthyAdvance) > 0) {
            throw new IllegalArgumentException("constrainedAdvance must not exceed healthyAdvance");
        }
    }

    public AdmissionRegulationResult handle(RegulateAdmissionCommand command) {
        Objects.requireNonNull(command, "command");
        return waitingRoomRepository.findAdmission(command.eventId())
                .map(current -> regulate(current, admissionHealthGateway.assess(command.eventId())))
                .orElseGet(() -> AdmissionRegulationResult.disabled(command.eventId()));
    }

    private AdmissionRegulationResult regulate(EventAdmission current, AdmissionCapacity capacity) {
        Objects.requireNonNull(capacity, "capacity");
        if (capacity == AdmissionCapacity.OVERLOADED) {
            return AdmissionRegulationResult.unchanged(current, capacity);
        }

        Duration step = capacity == AdmissionCapacity.HEALTHY ? healthyAdvance : constrainedAdvance;
        Instant now = clock.instant();
        Instant candidate = current.admittedThrough().plus(step);
        Instant target = candidate.isAfter(now) ? now : candidate;
        if (!target.isAfter(current.admittedThrough())) {
            return AdmissionRegulationResult.unchanged(current, capacity);
        }

        EventAdmission advanced = waitingRoomRepository.advanceAdmission(
                new EventAdmission(current.eventId(), target));
        return AdmissionRegulationResult.advanced(advanced, capacity);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
