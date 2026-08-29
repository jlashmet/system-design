package com.systemdesign.ticketmaster.controlplane.domain;

public final class WriterFenceNotConfirmedException extends RuntimeException {
    public WriterFenceNotConfirmedException(EventId eventId, RegionId ownerRegion, long ownershipEpoch) {
        super("authoritative writer fence is not confirmed for event " + eventId.value()
                + " in " + ownerRegion.value() + " at epoch " + ownershipEpoch);
    }
}
