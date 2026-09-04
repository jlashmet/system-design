package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import java.time.Instant;
import java.util.Objects;

public record StartCheckoutResult(Booking booking, String paymentIntentId, Instant checkoutExpiresAt) {
    public StartCheckoutResult {
        Objects.requireNonNull(booking, "booking");
        Objects.requireNonNull(paymentIntentId, "paymentIntentId");
        Objects.requireNonNull(checkoutExpiresAt, "checkoutExpiresAt");
        if (paymentIntentId.isBlank()) throw new IllegalArgumentException("paymentIntentId must not be blank");
    }
}
