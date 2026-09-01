package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.BookingStatus;
import com.systemdesign.ticketmaster.booking.domain.CheckoutConflictException;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import com.systemdesign.ticketmaster.booking.domain.PaymentProviderUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ReconcileBookingHandler {
    private final EventWriteAuthority eventWriteAuthority;
    private final BookingRepository bookingRepository;
    private final ReservationCheckoutService reservationCheckoutService;
    private final CheckoutGateway checkoutGateway;
    private final PaymentGateway paymentGateway;
    private final Clock clock;
    private final Duration pendingBackoff;

    public ReconcileBookingHandler(EventWriteAuthority eventWriteAuthority,
                                   BookingRepository bookingRepository,
                                   ReservationCheckoutService reservationCheckoutService,
                                   CheckoutGateway checkoutGateway, PaymentGateway paymentGateway,
                                   Clock clock, Duration pendingBackoff) {
        this.eventWriteAuthority = Objects.requireNonNull(eventWriteAuthority, "eventWriteAuthority");
        this.bookingRepository = Objects.requireNonNull(bookingRepository, "bookingRepository");
        this.reservationCheckoutService = Objects.requireNonNull(reservationCheckoutService, "reservationCheckoutService");
        this.checkoutGateway = Objects.requireNonNull(checkoutGateway, "checkoutGateway");
        this.paymentGateway = Objects.requireNonNull(paymentGateway, "paymentGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pendingBackoff = Objects.requireNonNull(pendingBackoff, "pendingBackoff");
        if (pendingBackoff.isZero() || pendingBackoff.isNegative()) {
            throw new IllegalArgumentException("pendingBackoff must be positive");
        }
    }

    public Booking handle(BookingId bookingId) {
        Booking booking = loadBooking(bookingId);
        if (booking.status() != BookingStatus.PENDING_PAYMENT) return booking;
        eventWriteAuthority.assertMayWrite(booking.eventId());
        return reconcileAuthorized(booking);
    }

    public Booking handle(EventId eventId, BookingId bookingId) {
        Objects.requireNonNull(eventId, "eventId");
        eventWriteAuthority.assertMayWrite(eventId);
        Booking booking = loadBooking(bookingId);
        if (!booking.eventId().equals(eventId)) {
            throw new IllegalArgumentException("booking does not belong to event " + eventId.value());
        }
        if (booking.status() != BookingStatus.PENDING_PAYMENT) return booking;
        return reconcileAuthorized(booking);
    }

    public void deferAfterFailure(Booking booking) {
        Objects.requireNonNull(booking, "booking");
        if (booking.status() != BookingStatus.PENDING_PAYMENT) return;
        eventWriteAuthority.assertMayWrite(booking.eventId());
        reschedule(booking);
    }

    private Booking reconcileAuthorized(Booking booking) {
        ReservationCheckout reservation = loadReservation(booking);
        Booking withIntent = ensurePaymentIntent(booking);
        PaymentIntentStatus paymentStatus = getPaymentStatusOrReschedule(withIntent);
        if (paymentStatus == PaymentIntentStatus.SUCCEEDED) return confirm(withIntent, reservation);
        if (paymentStatus == PaymentIntentStatus.FAILED || paymentStatus == PaymentIntentStatus.CANCELED) {
            return fail(withIntent, reservation);
        }
        Instant checkoutDeadline = Objects.requireNonNull(
                reservation.checkoutExpiresAt(), "pending booking hold must have checkout expiration");
        if (!clock.instant().isBefore(checkoutDeadline)) {
            PaymentIntentStatus cancelStatus = cancelPaymentOrReschedule(withIntent);
            if (cancelStatus == PaymentIntentStatus.SUCCEEDED) return confirm(withIntent, reservation);
            if (cancelStatus == PaymentIntentStatus.FAILED || cancelStatus == PaymentIntentStatus.CANCELED) {
                return fail(withIntent, reservation);
            }
        }
        return reschedule(withIntent);
    }

    private Booking loadBooking(BookingId bookingId) {
        return bookingRepository.findById(Objects.requireNonNull(bookingId, "bookingId"))
                .orElseThrow(() -> new IllegalArgumentException("booking not found: " + bookingId.value()));
    }

    private PaymentIntentStatus getPaymentStatusOrReschedule(Booking booking) {
        try {
            return paymentGateway.getPaymentStatus(booking.paymentIntentIdOptional().orElseThrow());
        } catch (PaymentProviderUnavailableException unavailable) {
            reschedule(booking);
            throw unavailable;
        }
    }

    private PaymentIntentStatus cancelPaymentOrReschedule(Booking booking) {
        try {
            return paymentGateway.cancelPaymentIntent(booking.paymentIntentIdOptional().orElseThrow());
        } catch (PaymentProviderUnavailableException unavailable) {
            reschedule(booking);
            throw unavailable;
        }
    }

    private Booking confirm(Booking booking, ReservationCheckout reservation) {
        Booking confirmed = booking.confirm();
        try {
            checkoutGateway.finalizeBooking(reservation, confirmed);
            return confirmed;
        } catch (CheckoutConflictException conflict) {
            return terminalAfterConcurrentFinalization(booking.id(), BookingStatus.CONFIRMED, conflict);
        }
    }

    private Booking fail(Booking booking, ReservationCheckout reservation) {
        Booking failedBooking = booking.fail();
        try {
            checkoutGateway.failBooking(reservation, failedBooking);
            return failedBooking;
        } catch (CheckoutConflictException conflict) {
            return terminalAfterConcurrentFinalization(booking.id(), BookingStatus.FAILED, conflict);
        }
    }

    private Booking terminalAfterConcurrentFinalization(
            BookingId bookingId,
            BookingStatus expectedStatus,
            CheckoutConflictException conflict) {
        Booking current = loadBooking(bookingId);
        if (current.status() == expectedStatus) return current;
        throw conflict;
    }

    private Booking ensurePaymentIntent(Booking booking) {
        if (booking.paymentIntentIdOptional().isPresent()) return booking;
        PaymentIntent intent;
        try {
            intent = paymentGateway.createPaymentIntent(
                    booking.eventId(), booking.id(), booking.totalPrice(), booking.id().value());
        } catch (PaymentProviderUnavailableException unavailable) {
            reschedule(booking);
            throw unavailable;
        }
        Booking withIntent = booking.attachPaymentIntent(intent.id());
        bookingRepository.savePaymentIntent(withIntent);
        return withIntent;
    }

    private Booking reschedule(Booking booking) {
        Instant nextAttempt = clock.instant().plus(pendingBackoff);
        if (!nextAttempt.isAfter(booking.nextReconcileAt())) return booking;
        Booking rescheduled = booking.rescheduleReconciliation(nextAttempt);
        bookingRepository.rescheduleReconciliation(rescheduled);
        return rescheduled;
    }

    private ReservationCheckout loadReservation(Booking booking) {
        ReservationCheckout reservation = reservationCheckoutService.findById(booking.holdId())
                .orElseThrow(() -> new IllegalStateException("hold not found for booking: " + booking.id().value()));
        return ReconciliationScopeGuard.requireMatchingReservation(booking, reservation);
    }
}
