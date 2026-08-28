package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import java.util.Objects;

public record StartCheckoutResult(Booking booking, String paymentIntentId) {
    public StartCheckoutResult {
        Objects.requireNonNull(booking, "booking");
        Objects.requireNonNull(paymentIntentId, "paymentIntentId");
        if (paymentIntentId.isBlank()) throw new IllegalArgumentException("paymentIntentId must not be blank");
    }
}
