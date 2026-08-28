package com.systemdesign.ticketmaster.booking.domain;

/**
 * Anti-corruption boundary for the global booking ownership control plane.
 * Implementations must fail closed when this deployment cannot prove that it
 * currently owns authoritative writes for the event.
 */
public interface EventOwnershipGate {
    void requireLocalOwnership(EventId eventId);
}
