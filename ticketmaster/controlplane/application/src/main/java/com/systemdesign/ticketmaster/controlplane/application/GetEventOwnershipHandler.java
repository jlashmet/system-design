package com.systemdesign.ticketmaster.controlplane.application;

import com.systemdesign.ticketmaster.controlplane.domain.EventOwnership;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnershipRepository;
import java.util.Objects;
import java.util.Optional;

public final class GetEventOwnershipHandler {
    private final EventOwnershipRepository repository;

    public GetEventOwnershipHandler(EventOwnershipRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Optional<EventOwnership> handle(GetEventOwnershipQuery query) {
        Objects.requireNonNull(query, "query");
        return repository.findByEventId(query.eventId());
    }
}
