package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.AdmissionCapacity;
import com.systemdesign.ticketmaster.booking.domain.AdmissionHealthGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.util.Objects;

/**
 * Explicit bootstrap adapter for exercising admission regulation before a production
 * telemetry-backed health source is connected. The default deployment configuration
 * should use OVERLOADED so admission never advances accidentally.
 */
public final class ConfiguredAdmissionHealthGateway implements AdmissionHealthGateway {
    private final AdmissionCapacity capacity;

    public ConfiguredAdmissionHealthGateway(AdmissionCapacity capacity) {
        this.capacity = Objects.requireNonNull(capacity, "capacity");
    }

    @Override
    public AdmissionCapacity assess(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return capacity;
    }
}
