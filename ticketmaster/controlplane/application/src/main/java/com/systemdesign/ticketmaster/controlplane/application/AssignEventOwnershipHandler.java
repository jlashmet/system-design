package com.systemdesign.ticketmaster.controlplane.application;

import com.systemdesign.ticketmaster.controlplane.domain.EventOwnership;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnershipRepository;
import java.util.Objects;

public final class AssignEventOwnershipHandler {
    private final EventOwnershipRepository repository;

    public AssignEventOwnershipHandler(EventOwnershipRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public EventOwnership handle(AssignEventOwnershipCommand command) {
        Objects.requireNonNull(command, "command");
        return repository.assignIfAbsent(new EventOwnership(command.eventId(), command.ownerRegion(), 1));
    }
}
