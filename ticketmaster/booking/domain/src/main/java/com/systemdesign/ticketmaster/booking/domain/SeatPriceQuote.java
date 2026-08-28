package com.systemdesign.ticketmaster.booking.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record SeatPriceQuote(EventId eventId, Map<SeatId, Price> prices) {
    public SeatPriceQuote {
        Objects.requireNonNull(eventId, "eventId");
        prices = Map.copyOf(prices);
        if (prices.isEmpty()) throw new IllegalArgumentException("price quote must contain at least one seat");
        prices.forEach((seatId, price) -> {
            Objects.requireNonNull(seatId, "seatId");
            Objects.requireNonNull(price, "price");
        });
        Currency currency = prices.values().iterator().next().currency();
        if (prices.values().stream().anyMatch(price -> !currency.equals(price.currency()))) {
            throw new IllegalArgumentException("all quoted seat prices must use the same currency");
        }
    }

    public Set<SeatId> seatIds() {
        return prices.keySet();
    }

    public Price totalPrice() {
        Currency currency = prices.values().iterator().next().currency();
        BigDecimal total = prices.values().stream()
                .map(Price::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Price(total, currency);
    }
}
