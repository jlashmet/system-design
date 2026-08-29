package com.systemdesign.ticketmaster.booking.infrastructure.input;

public final class PaymentWebhookAuthenticationException extends RuntimeException {
    public PaymentWebhookAuthenticationException() {
        super("payment webhook authentication failed");
    }

    public PaymentWebhookAuthenticationException(Throwable cause) {
        super("payment webhook authentication failed", cause);
    }
}
