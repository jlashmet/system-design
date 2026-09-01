package com.systemdesign.ticketmaster.booking.domain;

public enum PaymentIntentStatus {
    REQUIRES_PAYMENT_METHOD,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELED
}
