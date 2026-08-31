package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.util.Objects;

public record EnableAdmissionCommand(EventId eventId) {
    public EnableAdmissionCommand {
        Objects.requireNonNull(eventId, "eventId");
    }
}
