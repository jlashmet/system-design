package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public record PaymentIntent(String id, PaymentIntentStatus status) {
    public PaymentIntent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        if (id.isBlank()) throw new IllegalArgumentException("payment intent id must not be blank");
    }
}
