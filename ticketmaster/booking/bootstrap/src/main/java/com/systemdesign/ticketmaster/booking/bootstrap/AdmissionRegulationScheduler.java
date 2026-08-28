package com.systemdesign.ticketmaster.booking.bootstrap;

import com.systemdesign.ticketmaster.booking.application.EnableAdmissionCommand;
import com.systemdesign.ticketmaster.booking.application.EnableAdmissionHandler;
import com.systemdesign.ticketmaster.booking.application.RegulateAdmissionCommand;
import com.systemdesign.ticketmaster.booking.application.RegulateAdmissionHandler;
import com.systemdesign.ticketmaster.booking.domain.AdmissionRegulationLeaseGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class AdmissionRegulationScheduler {
    private static final System.Logger LOGGER = System.getLogger(AdmissionRegulationScheduler.class.getName());

    private final EventWriteAuthority eventWriteAuthority;
    private final RegulateAdmissionHandler handler;
    private final AdmissionRegulationLeaseGateway leaseGateway;
    private final Clock clock;
    private final Duration leaseDuration;
    private final String regulatorId;
    private final List<EventId> eventIds;

    public AdmissionRegulationScheduler(
            EnableAdmissionHandler enableAdmissionHandler,
            EventWriteAuthority eventWriteAuthority,
            RegulateAdmissionHandler handler,
            AdmissionRegulationLeaseGateway leaseGateway,
            Clock clock,
            Duration leaseDuration,
            String regulatorId,
            List<EventId> eventIds) {
        Objects.requireNonNull(enableAdmissionHandler, "enableAdmissionHandler");
        this.eventWriteAuthority = Objects.requireNonNull(eventWriteAuthority, "eventWriteAuthority");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.leaseGateway = Objects.requireNonNull(leaseGateway, "leaseGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.regulatorId = Objects.requireNonNull(regulatorId, "regulatorId");
        if (regulatorId.isBlank()) throw new IllegalArgumentException("regulatorId must not be blank");
        this.eventIds = List.copyOf(Objects.requireNonNull(eventIds, "eventIds"));

        // Any region may initialize the globally consistent waiting-room record, but only the
        // event's authoritative booking region may later regulate it.
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
                try {
                    eventWriteAuthority.assertMayWrite(eventId);
                } catch (WrongBookingRegionException notHomeRegion) {
                    continue;
                }

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
                // Fail closed: any authority/control-plane/health/storage failure holds the
                // watermark steady for this event while allowing other events to continue.
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
