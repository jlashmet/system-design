package com.systemdesign.ticketmaster.controlplane.domain;

public final class OwnershipConflictException extends RuntimeException {
    public OwnershipConflictException(String message) {
        super(message);
    }
}
