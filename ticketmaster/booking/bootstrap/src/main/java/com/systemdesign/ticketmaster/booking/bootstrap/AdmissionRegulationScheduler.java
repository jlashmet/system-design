package com.systemdesign.ticketmaster.booking.bootstrap;

import com.systemdesign.ticketmaster.booking.application.EnableAdmissionCommand;
import com.systemdesign.ticketmaster.booking.application.EnableAdmissionHandler;
import com.systemdesign.ticketmaster.booking.application.RegulateAdmissionCommand;
import com.systemdesign.ticketmaster.booking.application.RegulateAdmissionHandler;
import com.systemdesign.ticketmaster.booking.domain.AdmissionRegulationLeaseGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class AdmissionRegulationScheduler {
    private static final System.Logger LOGGER = System.getLogger(AdmissionRegulationScheduler.class.getName());

    private final RegulateAdmissionHandler handler;
    private final AdmissionRegulationLeaseGateway leaseGateway;
    private final Clock clock;
    private final Duration leaseDuration;
    private final String regulatorId;
    private final List<EventId> eventIds;

    public AdmissionRegulationScheduler(
            EnableAdmissionHandler enableAdmissionHandler,
            RegulateAdmissionHandler handler,
            AdmissionRegulationLeaseGateway leaseGateway,
            Clock clock,
            Duration leaseDuration,
            String regulatorId,
            List<EventId> eventIds) {
        Objects.requireNonNull(enableAdmissionHandler, "enableAdmissionHandler");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.leaseGateway = Objects.requireNonNull(leaseGateway, "leaseGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.regulatorId = Objects.requireNonNull(regulatorId, "regulatorId");
        if (regulatorId.isBlank()) throw new IllegalArgumentException("regulatorId must not be blank");
        this.eventIds = List.copyOf(Objects.requireNonNull(eventIds, "eventIds"));

        // Fail startup rather than accidentally serve a configured hot event with admission disabled.
        for (EventId eventId : this.eventIds) {
            enableAdmissionHandler.handle(new EnableAdmissionCommand(eventId));
        }
    }

    @Scheduled(
            initialDelayString = "${ticketmaster.booking.admission.initial-delay-ms:1000}",
            fixedDelayString = "${ticketmaster.booking.admission.poll-delay-ms:1000}")
    public void regulate() {
        for (EventId eventId : eventIds) {
            try {
                Instant now = clock.instant();
                if (!leaseGateway.tryAcquireOrRenew(
                        eventId,
                        regulatorId,
                        now,
                        now.plus(leaseDuration))) {
                    continue;
                }
                handler.handle(new RegulateAdmissionCommand(eventId));
            } catch (RuntimeException failure) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "admission regulation failed for event " + eventId.value(), failure);
            }
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
