package com.systemdesign.ticketmaster.search.infrastructure.output;

public final class OpenSearchQueryException extends RuntimeException {
    public OpenSearchQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
