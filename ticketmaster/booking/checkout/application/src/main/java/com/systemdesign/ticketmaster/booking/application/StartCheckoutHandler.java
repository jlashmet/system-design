package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.CheckoutConflictException;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import com.systemdesign.ticketmaster.booking.domain.HoldNotFoundException;
import com.systemdesign.ticketmaster.booking.domain.HoldOwnershipException;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutService;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class StartCheckoutHandler {
    private final EventWriteAuthority eventWriteAuthority;
    private final ReservationCheckoutService reservationCheckoutService;
    private final BookingRepository bookingRepository;
    private final CheckoutGateway checkoutGateway;
    private final PaymentGateway paymentGateway;
    private final Clock clock;
    private final Duration checkoutDuration;
    private final Duration reconciliationDelay;
    private final int reconciliationShards;

    public StartCheckoutHandler(EventWriteAuthority eventWriteAuthority,
                                ReservationCheckoutService reservationCheckoutService,
                                BookingRepository bookingRepository,
                                CheckoutGateway checkoutGateway, PaymentGateway paymentGateway,
                                Clock clock, Duration checkoutDuration, Duration reconciliationDelay,
                                int reconciliationShards) {
        this.eventWriteAuthority = Objects.requireNonNull(eventWriteAuthority, "eventWriteAuthority");
        this.reservationCheckoutService = Objects.requireNonNull(reservationCheckoutService, "reservationCheckoutService");
        this.bookingRepository = Objects.requireNonNull(bookingRepository, "bookingRepository");
        this.checkoutGateway = Objects.requireNonNull(checkoutGateway, "checkoutGateway");
        this.paymentGateway = Objects.requireNonNull(paymentGateway, "paymentGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.checkoutDuration = requirePositive(checkoutDuration, "checkoutDuration");
        this.reconciliationDelay = requirePositive(reconciliationDelay, "reconciliationDelay");
        if (reconciliationShards < 1) throw new IllegalArgumentException("reconciliationShards must be positive");
        this.reconciliationShards = reconciliationShards;
    }

    public StartCheckoutResult handle(StartCheckoutCommand command) {
        Objects.requireNonNull(command, "command");
        eventWriteAuthority.assertMayWrite(command.eventId());
        return findIdempotentBooking(command)
                .map(booking -> ensureSameScope(command, booking))
                .map(this::ensurePaymentIntent)
                .orElseGet(() -> startNewCheckout(command));
    }

    private StartCheckoutResult startNewCheckout(StartCheckoutCommand command) {
        Instant now = clock.instant();
        ReservationCheckout reservation = reservationCheckoutService.prepareCheckout(
                command.eventId(), command.holdId(), command.userId(), now, now.plus(checkoutDuration));
        BookingId bookingId = new BookingId(UUID.randomUUID().toString());
        int shard = Math.floorMod(bookingId.value().hashCode(), reconciliationShards);
        Booking booking = Booking.pending(bookingId, reservation, command.idempotencyKey(), now,
                now.plus(reconciliationDelay), shard);

        try {
            checkoutGateway.startCheckout(reservation, booking);
            return ensurePaymentIntent(booking);
        } catch (CheckoutConflictException conflict) {
            return findIdempotentBooking(command)
                    .map(bookingAfterConflict -> ensureSameScope(command, bookingAfterConflict))
                    .map(this::ensurePaymentIntent)
                    .orElseThrow(() -> conflict);
        }
    }

    private java.util.Optional<Booking> findIdempotentBooking(StartCheckoutCommand command) {
        return bookingRepository.findByCheckoutIdempotencyKey(
                command.eventId(), command.holdId(), command.idempotencyKey());
    }

    private Booking ensureSameScope(StartCheckoutCommand command, Booking booking) {
        if (!booking.eventId().equals(command.eventId()) || !booking.holdId().equals(command.holdId())) {
            throw new IllegalStateException("checkout idempotency mapping resolved outside its event/hold scope");
        }
        requireOwner(command, booking.userId());
        return booking;
    }

    private void requireOwner(StartCheckoutCommand command, com.systemdesign.ticketmaster.booking.domain.UserId owner) {
        if (!owner.equals(command.userId())) {
            throw new HoldOwnershipException(command.holdId(), command.userId());
        }
    }

    private StartCheckoutResult ensurePaymentIntent(Booking booking) {
        if (booking.paymentIntentIdOptional().isPresent()) {
            return new StartCheckoutResult(booking, booking.paymentIntentIdOptional().orElseThrow());
        }

        ReservationCheckout reservation = reservationCheckoutService.findById(booking.holdId())
                .orElseThrow(() -> new HoldNotFoundException(booking.holdId()));
        requirePaymentReservationScope(booking, reservation);

        PaymentIntent intent = paymentGateway.createPaymentIntent(
                booking.eventId(), booking.id(), booking.totalPrice(), booking.id().value());
        Booking withIntent = booking.attachPaymentIntent(intent.id());
        bookingRepository.savePaymentIntent(withIntent);
        return new StartCheckoutResult(withIntent, intent.id());
    }

    private static void requirePaymentReservationScope(Booking booking, ReservationCheckout reservation) {
        if (!reservation.id().equals(booking.holdId()) || !reservation.eventId().equals(booking.eventId())) {
            throw new IllegalStateException("checkout reservation scope mismatch for " + booking.id().value());
        }
        if (!reservation.userId().equals(booking.userId())) {
            throw new HoldOwnershipException(booking.holdId(), booking.userId());
        }
        if (!reservation.totalPrice().equals(booking.totalPrice())) {
            throw new IllegalStateException("checkout reservation price mismatch for " + booking.id().value());
        }
        if (reservation.status() != ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS) {
            throw new IllegalStateException("checkout reservation is not in checkout for " + booking.id().value());
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return duration;
    }
}
