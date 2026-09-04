package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.AdmissionAccess;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.PreparedCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutService;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Reservation-owned preparation and lookup boundary used by Checkout. */
public final class ReservationCheckoutServiceImpl implements ReservationCheckoutService {
    private final HoldRepository holdRepository;
    private final AdmissionAccess admissionAccess;

    public ReservationCheckoutServiceImpl(HoldRepository holdRepository, AdmissionAccess admissionAccess) {
        this.holdRepository = Objects.requireNonNull(holdRepository, "holdRepository");
        this.admissionAccess = Objects.requireNonNull(admissionAccess, "admissionAccess");
    }

    @Override
    public PreparedCheckout prepareCheckout(EventId eventId, UserId userId, Set<SeatId> seatIds,
                                            String admissionToken, Instant now, Instant checkoutExpiresAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
        seatIds = Set.copyOf(Objects.requireNonNull(seatIds, "seatIds"));
        if (seatIds.isEmpty()) throw new IllegalArgumentException("at least one seat is required");
        if (seatIds.size() > 96) throw new IllegalArgumentException("checkout cannot contain more than 96 seats");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(checkoutExpiresAt, "checkoutExpiresAt");
        if (!checkoutExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("checkout expiration must be in the future");
        }

        admissionAccess.requireAdmission(eventId, userId, admissionToken, now);
        SeatPriceQuote quote = holdRepository.quoteSeatPrices(eventId, seatIds);
        if (!quote.eventId().equals(eventId) || !quote.seatIds().equals(seatIds)) {
            throw new IllegalStateException("seat price quote does not match requested event and seats");
        }

        ReservationCheckout reservation = new ReservationCheckout(
                new HoldId(UUID.randomUUID().toString()),
                userId,
                eventId,
                seatIds,
                quote.totalPrice(),
                ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS,
                checkoutExpiresAt);
        return new PreparedCheckout(reservation, quote.prices());
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
                hold.checkoutExpiresAt());
    }
}
