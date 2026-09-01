package com.systemdesign.ticketmaster.booking.reservation.infrastructure;

import java.util.Objects;

public final class ReservationStorageUnavailableException extends RuntimeException {
    public ReservationStorageUnavailableException(String operation, Throwable cause) {
        super("reservation storage unavailable during " + requireOperation(operation), cause);
    }

    private static String requireOperation(String operation) {
        Objects.requireNonNull(operation, "operation");
        if (operation.isBlank()) throw new IllegalArgumentException("operation must not be blank");
        return operation;
    }
}
