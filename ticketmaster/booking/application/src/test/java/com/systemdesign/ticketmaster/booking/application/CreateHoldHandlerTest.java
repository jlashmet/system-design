package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CreateHoldHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-27T22:00:00Z");
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final SeatId A10 = new SeatId("A10");
    private static final SeatId A11 = new SeatId("A11");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void computesHoldTotalFromAuthoritativeSeatQuote() {
        SeatPriceQuote quote = new SeatPriceQuote(EVENT_ID, Map.of(
                A10, price("100.00"),
                A11, price("125.00")));
        FakeHoldRepository repository = new FakeHoldRepository(quote);
        CreateHoldHandler handler = new CreateHoldHandler(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));

        Hold hold = handler.handle(new CreateHoldCommand(
                new UserId("user-456"),
                EVENT_ID,
                java.util.List.of(A10, A11)));

        assertThat(hold.totalPrice()).isEqualTo(price("225.00"));
        assertThat(hold.createdAt()).isEqualTo(NOW);
        assertThat(hold.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(repository.createdHold).isEqualTo(hold);
        assertThat(repository.claimQuote).isEqualTo(quote);
    }

    private static Price price(String amount) {
        return new Price(new BigDecimal(amount), USD);
    }

    private static final class FakeHoldRepository implements HoldRepository {
        private final SeatPriceQuote quote;
        private Hold createdHold;
        private SeatPriceQuote claimQuote;

        private FakeHoldRepository(SeatPriceQuote quote) {
            this.quote = quote;
        }

        @Override
        public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) {
            assertThat(eventId).isEqualTo(quote.eventId());
            assertThat(seatIds).isEqualTo(quote.seatIds());
            return quote;
        }

        @Override
        public void createWithSeatClaims(Hold hold, SeatPriceQuote quote, Instant now) {
            this.createdHold = hold;
            this.claimQuote = quote;
            assertThat(now).isEqualTo(NOW);
        }

        @Override
        public Optional<Hold> findById(HoldId holdId) {
            return Optional.ofNullable(createdHold).filter(hold -> hold.id().equals(holdId));
        }
    }
}
