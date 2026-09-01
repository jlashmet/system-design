package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldNotFoundException;
import com.systemdesign.ticketmaster.booking.domain.HoldOwnershipException;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutService;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Reservation-owned adapter from the Hold aggregate to the Checkout-facing contract. */
public final class ReservationCheckoutServiceImpl implements ReservationCheckoutService {
    private final HoldRepository holdRepository;

    public ReservationCheckoutServiceImpl(HoldRepository holdRepository) {
        this.holdRepository = Objects.requireNonNull(holdRepository, "holdRepository");
    }

    @Override
    public ReservationCheckout prepareCheckout(EventId eventId, HoldId holdId, UserId userId,
                                                Instant now, Instant checkoutExpiresAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(checkoutExpiresAt, "checkoutExpiresAt");
        Hold hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));
        if (!hold.eventId().equals(eventId)) {
            throw new IllegalArgumentException("hold does not belong to event " + eventId.value());
        }
        if (!hold.userId().equals(userId)) {
            throw new HoldOwnershipException(holdId, userId);
        }
        return snapshot(hold.startCheckout(now, checkoutExpiresAt));
    }

    @Override
    public Optional<ReservationCheckout> findById(HoldId holdId) {
        Objects.requireNonNull(holdId, "holdId");
        return holdRepository.findById(holdId).map(ReservationCheckoutServiceImpl::snapshot);
    }

    private static ReservationCheckout snapshot(Hold hold) {
        return new ReservationCheckout(
                hold.id(), hold.userId(), hold.eventId(), hold.seatIds(), hold.totalPrice(),
                ReservationCheckoutStatus.valueOf(hold.status().name()),
                hold.expiresAt(), hold.checkoutExpiresAt());
    }
}
