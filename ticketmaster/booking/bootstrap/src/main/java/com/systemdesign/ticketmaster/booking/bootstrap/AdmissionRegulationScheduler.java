package com.systemdesign.ticketmaster.booking.bootstrap;

import com.systemdesign.ticketmaster.booking.application.RegulateAdmissionCommand;
import com.systemdesign.ticketmaster.booking.application.RegulateAdmissionHandler;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.util.List;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class AdmissionRegulationScheduler {
    private final RegulateAdmissionHandler handler;
    private final List<EventId> eventIds;

    public AdmissionRegulationScheduler(RegulateAdmissionHandler handler, List<EventId> eventIds) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.eventIds = List.copyOf(Objects.requireNonNull(eventIds, "eventIds"));
    }

    @Scheduled(
            initialDelayString = "${ticketmaster.booking.admission.initial-delay-ms:1000}",
            fixedDelayString = "${ticketmaster.booking.admission.poll-delay-ms:1000}")
    public void regulate() {
        for (EventId eventId : eventIds) {
            handler.handle(new RegulateAdmissionCommand(eventId));
        }
    }
}
