package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public final class PaymentProviderUnavailableException extends RuntimeException {
    private final String operation;

    public PaymentProviderUnavailableException(String operation, Throwable cause) {
        super("payment provider unavailable during " + requireNonBlank(operation), Objects.requireNonNull(cause, "cause"));
        this.operation = operation;
    }

    public String operation() {
        return operation;
    }

    private static String requireNonBlank(String value) {
        Objects.requireNonNull(value, "operation");
        if (value.isBlank()) throw new IllegalArgumentException("operation must not be blank");
        return value;
    }
}
