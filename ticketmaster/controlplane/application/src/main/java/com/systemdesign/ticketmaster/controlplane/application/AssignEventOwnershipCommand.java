package com.systemdesign.ticketmaster.controlplane.application;

import com.systemdesign.ticketmaster.controlplane.domain.EventId;
import com.systemdesign.ticketmaster.controlplane.domain.RegionId;
import java.util.Objects;

public record AssignEventOwnershipCommand(EventId eventId, RegionId ownerRegion) {
    public AssignEventOwnershipCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(ownerRegion, "ownerRegion");
    }
}
