package com.systemdesign.ticketmaster.booking.infrastructure.common;

import java.util.Objects;

public final class BookingStorageUnavailableException extends RuntimeException {
    public BookingStorageUnavailableException(String operation, Throwable cause) {
        super("booking storage unavailable during " + requireOperation(operation), cause);
    }

    private static String requireOperation(String operation) {
        Objects.requireNonNull(operation, "operation");
        if (operation.isBlank()) throw new IllegalArgumentException("operation must not be blank");
        return operation;
    }
}
