package com.systemdesign.ticketmaster.controlplane.infrastructure.output;

import com.systemdesign.ticketmaster.controlplane.domain.EventId;
import com.systemdesign.ticketmaster.controlplane.domain.EventWriterFence;
import com.systemdesign.ticketmaster.controlplane.domain.RegionId;
import com.systemdesign.ticketmaster.controlplane.domain.WriterFenceNotConfirmedException;
import java.util.Objects;

/**
 * Safe default until deployment-specific authoritative-writer isolation is integrated.
 */
public final class FailClosedEventWriterFence implements EventWriterFence {
    @Override
    public void assertFenced(EventId eventId, RegionId ownerRegion, long ownershipEpoch) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(ownerRegion, "ownerRegion");
        if (ownershipEpoch < 1) throw new IllegalArgumentException("ownershipEpoch must be at least 1");
        throw new WriterFenceNotConfirmedException(eventId, ownerRegion, ownershipEpoch);
    }
}
