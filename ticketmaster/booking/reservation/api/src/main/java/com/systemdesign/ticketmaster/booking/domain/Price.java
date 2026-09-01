package com.systemdesign.ticketmaster.booking.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record Price(BigDecimal amount, Currency currency) {
    public Price {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) throw new IllegalArgumentException("amount must not be negative");
    }
}
