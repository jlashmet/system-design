package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.util.Objects;

public record RegulateAdmissionCommand(EventId eventId) {
    public RegulateAdmissionCommand {
        Objects.requireNonNull(eventId, "eventId");
    }
}
