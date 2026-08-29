package com.systemdesign.ticketmaster.booking.domain;

import java.time.Instant;
import java.util.Objects;

public record AdmissionGrant(String token, Instant expiresAt) {
    public AdmissionGrant {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (token.isBlank()) throw new IllegalArgumentException("token must not be blank");
    }
}
