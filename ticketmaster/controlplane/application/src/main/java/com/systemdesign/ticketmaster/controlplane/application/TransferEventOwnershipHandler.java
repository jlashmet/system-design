package com.systemdesign.ticketmaster.controlplane.application;

import com.systemdesign.ticketmaster.controlplane.domain.EventOwnership;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnershipRepository;
import com.systemdesign.ticketmaster.controlplane.domain.EventWriterFence;
import com.systemdesign.ticketmaster.controlplane.domain.OwnershipConflictException;
import java.util.Objects;

public final class TransferEventOwnershipHandler {
    private final EventOwnershipRepository repository;
    private final EventWriterFence writerFence;

    public TransferEventOwnershipHandler(EventOwnershipRepository repository, EventWriterFence writerFence) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.writerFence = Objects.requireNonNull(writerFence, "writerFence");
    }

    public EventOwnership handle(TransferEventOwnershipCommand command) {
        Objects.requireNonNull(command, "command");
        EventOwnership current = repository.findByEventId(command.eventId())
                .orElseThrow(() -> new OwnershipConflictException("event ownership does not exist"));
        if (!current.ownerRegion().equals(command.expectedOwner()) || current.epoch() != command.expectedEpoch()) {
            throw new OwnershipConflictException("stale event ownership");
        }

        writerFence.assertFenced(command.eventId(), command.expectedOwner(), command.expectedEpoch());
        return repository.transfer(
                command.eventId(),
                command.expectedOwner(),
                command.expectedEpoch(),
                command.newOwner());
    }
}
