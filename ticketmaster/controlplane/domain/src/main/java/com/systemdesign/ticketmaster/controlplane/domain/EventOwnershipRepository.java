package com.systemdesign.ticketmaster.controlplane.domain;

import java.util.Optional;

public interface EventOwnershipRepository {
    Optional<EventOwnership> findByEventId(EventId eventId);

    EventOwnership assignIfAbsent(EventOwnership ownership);

    EventOwnership transfer(EventId eventId, RegionId expectedOwner, long expectedEpoch, RegionId newOwner);
}
