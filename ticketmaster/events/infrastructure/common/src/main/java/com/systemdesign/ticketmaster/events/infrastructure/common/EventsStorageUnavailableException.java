package com.systemdesign.ticketmaster.events.infrastructure.common;

import java.util.Objects;

public final class EventsStorageUnavailableException extends RuntimeException {
    public EventsStorageUnavailableException(String operation, Throwable cause) {
        super(Objects.requireNonNull(operation, "operation") + " is temporarily unavailable",
                Objects.requireNonNull(cause, "cause"));
    }
}
