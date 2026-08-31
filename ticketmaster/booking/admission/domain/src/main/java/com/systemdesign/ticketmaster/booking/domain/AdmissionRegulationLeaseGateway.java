package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;

public interface AdmissionRegulationLeaseGateway {
    boolean tryAcquireOrRenew(EventId eventId, String regulatorId, Instant now, Instant leaseExpiresAt);
}
