package com.systemdesign.ticketmaster.search.domain;

public final class SearchUnavailableException extends RuntimeException {
    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
