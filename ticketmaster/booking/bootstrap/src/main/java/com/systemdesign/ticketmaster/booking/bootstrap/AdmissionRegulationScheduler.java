package com.systemdesign.ticketmaster.booking.bootstrap;

import com.systemdesign.ticketmaster.booking.application.EnableAdmissionCommand;
import com.systemdesign.ticketmaster.booking.application.EnableAdmissionHandler;
import com.systemdesign.ticketmaster.booking.application.RegulateAdmissionCommand;
import com.systemdesign.ticketmaster.booking.application.RegulateAdmissionHandler;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.util.List;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class AdmissionRegulationScheduler {
    private static final System.Logger LOGGER = System.getLogger(AdmissionRegulationScheduler.class.getName());

    private final RegulateAdmissionHandler handler;
    private final List<EventId> eventIds;

    public AdmissionRegulationScheduler(
            EnableAdmissionHandler enableAdmissionHandler,
            RegulateAdmissionHandler handler,
            List<EventId> eventIds) {
        Objects.requireNonNull(enableAdmissionHandler, "enableAdmissionHandler");
        this.handler = Objects.requireNonNull(handler, "handler");
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
                handler.handle(new RegulateAdmissionCommand(eventId));
            } catch (RuntimeException failure) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "admission regulation failed for event " + eventId.value(), failure);
            }
        }
    }
}
