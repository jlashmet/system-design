package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.AdmissionCapacity;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.util.Objects;
import java.util.Optional;

public record AdmissionRegulationResult(
        EventId eventId,
        AdmissionCapacity capacity,
        Optional<EventAdmission> admission,
        boolean advanced) {
    public AdmissionRegulationResult {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(admission, "admission");
        if (admission.isEmpty() && capacity != null) {
            throw new IllegalArgumentException("disabled admission control must not report capacity");
        }
        if (admission.isPresent() && capacity == null) {
            throw new IllegalArgumentException("enabled admission control requires capacity");
        }
        if (advanced && admission.isEmpty()) {
            throw new IllegalArgumentException("disabled admission control cannot advance");
        }
    }

    public static AdmissionRegulationResult disabled(EventId eventId) {
        return new AdmissionRegulationResult(eventId, null, Optional.empty(), false);
    }

    public static AdmissionRegulationResult unchanged(EventAdmission admission, AdmissionCapacity capacity) {
        return new AdmissionRegulationResult(admission.eventId(), capacity, Optional.of(admission), false);
    }

    public static AdmissionRegulationResult advanced(EventAdmission admission, AdmissionCapacity capacity) {
        return new AdmissionRegulationResult(admission.eventId(), capacity, Optional.of(admission), true);
    }
}
