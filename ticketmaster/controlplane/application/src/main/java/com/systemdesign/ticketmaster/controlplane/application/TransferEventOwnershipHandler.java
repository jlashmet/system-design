package com.systemdesign.ticketmaster.controlplane.application;

import com.systemdesign.ticketmaster.controlplane.domain.EventOwnership;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnershipRepository;
import java.util.Objects;

public final class TransferEventOwnershipHandler {
    private final EventOwnershipRepository repository;

    public TransferEventOwnershipHandler(EventOwnershipRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public EventOwnership handle(TransferEventOwnershipCommand command) {
        Objects.requireNonNull(command, "command");
        return repository.transfer(
                command.eventId(),
                command.expectedOwner(),
                command.expectedEpoch(),
                command.newOwner());
    }
}
