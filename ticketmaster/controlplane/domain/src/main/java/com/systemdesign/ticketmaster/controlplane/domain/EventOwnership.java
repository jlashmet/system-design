package com.systemdesign.ticketmaster.controlplane.domain;

import java.util.Objects;

public record EventOwnership(EventId eventId, RegionId ownerRegion, long epoch) {
    public EventOwnership {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(ownerRegion, "ownerRegion");
        if (epoch < 1) throw new IllegalArgumentException("epoch must be at least 1");
    }
}
