package com.systemdesign.ticketmaster.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class EventSeatInventoryTest {
    private static final EventId EVENT_ID = new EventId("event-1");
    private static final SeatId SEAT_ID = new SeatId("A10");
    private static final HoldId CHECKOUT_ID = new HoldId("checkout-1");
    private static final HoldId OTHER_CHECKOUT = new HoldId("checkout-2");
    private static final BookingId BOOKING_ID = new BookingId("booking-1");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));
    private static final Instant NOW = Instant.parse("2026-08-27T20:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(600);

    @Test
    void availableSeatMovesDirectlyIntoCheckout() {
        EventSeatInventory result = EventSeatInventory.available(EVENT_ID, SEAT_ID, PRICE)
                .startCheckout(CHECKOUT_ID, NOW, DEADLINE);

        assertThat(result.status()).isEqualTo(SeatStatus.CHECKOUT);
        assertThat(result.holdId()).isEqualTo(CHECKOUT_ID);
        assertThat(result.checkoutExpiresAt()).isEqualTo(DEADLINE);
        assertThat(result.bookingId()).isNull();
    }

    @Test
    void checkoutCannotBeStolenEvenAfterCustomerDeadline() {
        EventSeatInventory checkout = EventSeatInventory.available(EVENT_ID, SEAT_ID, PRICE)
                .startCheckout(CHECKOUT_ID, NOW, DEADLINE);

        assertThat(checkout.isClaimableAt(DEADLINE.plusSeconds(1))).isFalse();
        assertThatThrownBy(() -> checkout.startCheckout(
                OTHER_CHECKOUT, DEADLINE.plusSeconds(1), DEADLINE.plusSeconds(601)))
                .isInstanceOf(SeatUnavailableException.class);
    }

    @Test
    void successfulCheckoutBooksSeat() {
        EventSeatInventory booked = EventSeatInventory.available(EVENT_ID, SEAT_ID, PRICE)
                .startCheckout(CHECKOUT_ID, NOW, DEADLINE)
                .book(CHECKOUT_ID, BOOKING_ID);

        assertThat(booked.status()).isEqualTo(SeatStatus.BOOKED);
        assertThat(booked.bookingId()).isEqualTo(BOOKING_ID);
        assertThat(booked.checkoutExpiresAt()).isNull();
    }

    @Test
    void safelyCanceledCheckoutReleasesSeat() {
        EventSeatInventory released = EventSeatInventory.available(EVENT_ID, SEAT_ID, PRICE)
                .startCheckout(CHECKOUT_ID, NOW, DEADLINE)
                .releaseCheckout(CHECKOUT_ID);

        assertThat(released).isEqualTo(EventSeatInventory.available(EVENT_ID, SEAT_ID, PRICE));
        assertThat(released.isClaimableAt(DEADLINE.plusSeconds(1))).isTrue();
    }
}
