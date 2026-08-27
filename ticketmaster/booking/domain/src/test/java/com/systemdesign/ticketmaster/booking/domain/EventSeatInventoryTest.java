package com.systemdesign.ticketmaster.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class EventSeatInventoryTest {
    private static final EventId EVENT_ID = new EventId("event-1");
    private static final SeatId SEAT_ID = new SeatId("A10");
    private static final HoldId OLD_HOLD = new HoldId("hold-old");
    private static final HoldId NEW_HOLD = new HoldId("hold-new");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));
    private static final Instant NOW = Instant.parse("2026-08-27T20:00:00Z");
    private static final Instant FUTURE = NOW.plusSeconds(300);

    private EventSeatInventory seat;
    private EventSeatInventory result;
    private RuntimeException failure;

    @Test
    void availableSeatCanBeHeld() {
        given(EventSeatInventory.available(EVENT_ID, SEAT_ID, PRICE));
        whenHold(NEW_HOLD, NOW, FUTURE);
        thenExpectHeldBy(NEW_HOLD, FUTURE);
    }

    @Test
    void expiredHoldCanBeReclaimedWithoutCleanup() {
        given(new EventSeatInventory(EVENT_ID, SEAT_ID, PRICE, SeatStatus.HELD, OLD_HOLD, NOW.minusSeconds(1), null));
        whenHold(NEW_HOLD, NOW, FUTURE);
        thenExpectHeldBy(NEW_HOLD, FUTURE);
    }

    @Test
    void activeHoldCannotBeStolen() {
        given(new EventSeatInventory(EVENT_ID, SEAT_ID, PRICE, SeatStatus.HELD, OLD_HOLD, FUTURE, null));
        whenHold(NEW_HOLD, NOW, FUTURE.plusSeconds(60));
        thenExpect(SeatUnavailableException.class);
    }

    private void given(EventSeatInventory seat) {
        this.seat = seat;
        this.result = null;
        this.failure = null;
    }

    private void whenHold(HoldId holdId, Instant now, Instant expiresAt) {
        try {
            result = seat.hold(holdId, now, expiresAt);
        } catch (RuntimeException exception) {
            failure = exception;
        }
    }

    private void thenExpectHeldBy(HoldId holdId, Instant expiresAt) {
        assertThat(failure).isNull();
        assertThat(result.status()).isEqualTo(SeatStatus.HELD);
        assertThat(result.holdId()).isEqualTo(holdId);
        assertThat(result.holdExpiresAt()).isEqualTo(expiresAt);
    }

    private void thenExpect(Class<? extends RuntimeException> type) {
        assertThat(result).isNull();
        assertThat(failure).isInstanceOf(type);
    }
}
