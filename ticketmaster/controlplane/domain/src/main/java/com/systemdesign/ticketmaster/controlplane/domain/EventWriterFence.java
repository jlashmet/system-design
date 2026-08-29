package com.systemdesign.ticketmaster.controlplane.domain;

/**
 * Safety boundary for authoritative booking writer isolation during regional failover.
 *
 * <p>An implementation may return normally only after the specified ownership generation is no
 * longer capable of mutating authoritative inventory. A control-plane ownership CAS is routing
 * metadata and is not itself a fence.</p>
 */
@FunctionalInterface
public interface EventWriterFence {
    void assertFenced(EventId eventId, RegionId ownerRegion, long ownershipEpoch);
}
