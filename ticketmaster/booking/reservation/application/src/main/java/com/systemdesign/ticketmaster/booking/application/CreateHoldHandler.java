package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.AdmissionAccess;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyConflictException;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatClaimConflictException;
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
    private final EventWriteAuthority eventWriteAuthority;
    private final HoldRepository holdRepository;
    private final AdmissionAccess admissionAccess;
    private final Clock clock;
    private final Duration holdDuration;

    public CreateHoldHandler(EventWriteAuthority eventWriteAuthority, HoldRepository holdRepository,
                             Clock clock, Duration holdDuration) {
        this(eventWriteAuthority, holdRepository, AdmissionAccess.disabled(), clock, holdDuration);
    }

    public CreateHoldHandler(EventWriteAuthority eventWriteAuthority, HoldRepository holdRepository,
                             AdmissionAccess admissionAccess, Clock clock, Duration holdDuration) {
        this.eventWriteAuthority = Objects.requireNonNull(eventWriteAuthority, "eventWriteAuthority");
        this.holdRepository = Objects.requireNonNull(holdRepository, "holdRepository");
        this.admissionAccess = Objects.requireNonNull(admissionAccess, "admissionAccess");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.holdDuration = Objects.requireNonNull(holdDuration, "holdDuration");
        if (holdDuration.isNegative() || holdDuration.isZero()) throw new IllegalArgumentException("hold duration must be positive");
    }

    public Hold handle(CreateHoldCommand command) {
        Objects.requireNonNull(command, "command");
        Set<SeatId> seats = new LinkedHashSet<>(command.seatIds());
        if (seats.size() != command.seatIds().size()) throw new IllegalArgumentException("duplicate seats are not allowed");

        eventWriteAuthority.assertMayWrite(command.eventId());
        Hold existing = findIdempotentHold(command).orElse(null);
        if (existing != null) return requireSameRequest(command, seats, existing);

        admissionAccess.requireAdmission(command.eventId(), command.userId(), command.admissionToken(), clock.instant());
        SeatPriceQuote quote = holdRepository.quoteSeatPrices(command.eventId(), seats);
        if (!quote.eventId().equals(command.eventId()) || !quote.seatIds().equals(seats)) {
            throw new IllegalStateException("seat price quote does not match requested event and seats");
        }

        Instant now = clock.instant();
        Hold hold = Hold.active(new HoldId(UUID.randomUUID().toString()), command.userId(), command.eventId(),
                seats, quote.totalPrice(), now, now.plus(holdDuration));
        try {
            holdRepository.createWithSeatClaims(hold, quote, now, command.idempotencyKey());
            return hold;
        } catch (SeatClaimConflictException conflict) {
            return findIdempotentHold(command)
                    .map(previous -> requireSameRequest(command, seats, previous))
                    .orElseThrow(() -> conflict);
        }
    }

    private java.util.Optional<Hold> findIdempotentHold(CreateHoldCommand command) {
        return holdRepository.findByIdempotencyKey(
                command.eventId(), command.userId(), command.idempotencyKey());
    }

    private Hold requireSameRequest(CreateHoldCommand command, Set<SeatId> seats, Hold existing) {
        if (!existing.userId().equals(command.userId()) || !existing.eventId().equals(command.eventId())
                || !existing.seatIds().equals(seats)) {
            throw new HoldIdempotencyConflictException(command.idempotencyKey());
        }
        return existing;
    }
}
