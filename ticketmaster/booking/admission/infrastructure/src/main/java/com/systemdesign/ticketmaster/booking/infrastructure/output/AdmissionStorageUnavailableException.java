package com.systemdesign.ticketmaster.booking.infrastructure.output;

import java.util.Objects;

public final class AdmissionStorageUnavailableException extends RuntimeException {
    public AdmissionStorageUnavailableException(String operation, Throwable cause) {
        super("admission storage unavailable during " + requireOperation(operation), cause);
    }

    private static String requireOperation(String operation) {
        Objects.requireNonNull(operation, "operation");
        if (operation.isBlank()) throw new IllegalArgumentException("operation must not be blank");
        return operation;
    }
}
