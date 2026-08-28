package com.systemdesign.ticketmaster.controlplane.application;

import com.systemdesign.ticketmaster.controlplane.domain.EventId;
import com.systemdesign.ticketmaster.controlplane.domain.RegionId;
import java.util.Objects;

public record TransferEventOwnershipCommand(
        EventId eventId,
        RegionId expectedOwner,
        long expectedEpoch,
        RegionId newOwner) {

    public TransferEventOwnershipCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(expectedOwner, "expectedOwner");
        Objects.requireNonNull(newOwner, "newOwner");
        if (expectedEpoch < 1) throw new IllegalArgumentException("expected epoch must be at least 1");
        if (expectedOwner.equals(newOwner)) throw new IllegalArgumentException("new owner must differ from expected owner");
    }
}
