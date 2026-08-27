package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.time.Instant;
import java.util.Objects;

public record AdvanceAdmissionCommand(EventId eventId, Instant admittedThrough) {
    public AdvanceAdmissionCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(admittedThrough, "admittedThrough");
    }
}
