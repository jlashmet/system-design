package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class CreateHoldHandler {
    private final HoldRepository holdRepository;
    private final Clock clock;
    private final Duration holdDuration;

    public CreateHoldHandler(HoldRepository holdRepository, Clock clock, Duration holdDuration) {
        this.holdRepository = Objects.requireNonNull(holdRepository, "holdRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.holdDuration = Objects.requireNonNull(holdDuration, "holdDuration");
        if (holdDuration.isNegative() || holdDuration.isZero()) throw new IllegalArgumentException("hold duration must be positive");
    }

    public Hold handle(CreateHoldCommand command) {
        Objects.requireNonNull(command, "command");
        Set<SeatId> seats = new LinkedHashSet<>(command.seatIds());
        if (seats.size() != command.seatIds().size()) throw new IllegalArgumentException("duplicate seats are not allowed");

        SeatPriceQuote quote = holdRepository.quoteSeatPrices(command.eventId(), seats);
        if (!quote.eventId().equals(command.eventId()) || !quote.seatIds().equals(seats)) {
            throw new IllegalStateException("seat price quote does not match requested event and seats");
        }

        Instant now = clock.instant();
        Hold hold = Hold.active(new HoldId(UUID.randomUUID().toString()), command.userId(), command.eventId(),
                seats, quote.totalPrice(), now, now.plus(holdDuration));
        holdRepository.createWithSeatClaims(hold, quote, now);
        return hold;
    }
}
