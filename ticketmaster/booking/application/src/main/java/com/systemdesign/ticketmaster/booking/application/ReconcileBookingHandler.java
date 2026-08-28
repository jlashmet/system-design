package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.BookingStatus;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ReconcileBookingHandler {
    private final BookingRepository bookingRepository;
    private final HoldRepository holdRepository;
    private final CheckoutGateway checkoutGateway;
    private final PaymentGateway paymentGateway;
    private final Clock clock;
    private final Duration pendingBackoff;

    public ReconcileBookingHandler(BookingRepository bookingRepository, HoldRepository holdRepository,
                                   CheckoutGateway checkoutGateway, PaymentGateway paymentGateway,
                                   Clock clock, Duration pendingBackoff) {
        this.bookingRepository = Objects.requireNonNull(bookingRepository, "bookingRepository");
        this.holdRepository = Objects.requireNonNull(holdRepository, "holdRepository");
        this.checkoutGateway = Objects.requireNonNull(checkoutGateway, "checkoutGateway");
        this.paymentGateway = Objects.requireNonNull(paymentGateway, "paymentGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pendingBackoff = Objects.requireNonNull(pendingBackoff, "pendingBackoff");
        if (pendingBackoff.isZero() || pendingBackoff.isNegative()) {
            throw new IllegalArgumentException("pendingBackoff must be positive");
        }
    }

    public Booking handle(BookingId bookingId) {
        Booking booking = bookingRepository.findById(Objects.requireNonNull(bookingId, "bookingId"))
                .orElseThrow(() -> new IllegalArgumentException("booking not found: " + bookingId.value()));
        if (booking.status() != BookingStatus.PENDING_PAYMENT) return booking;

        Booking withIntent = ensurePaymentIntent(booking);
        Hold hold = loadHold(withIntent);
        PaymentIntentStatus paymentStatus = getPaymentStatusOrReschedule(withIntent);

        if (paymentStatus == PaymentIntentStatus.SUCCEEDED) {
            return confirm(withIntent, hold);
        }
        if (paymentStatus == PaymentIntentStatus.FAILED || paymentStatus == PaymentIntentStatus.CANCELED) {
            return fail(withIntent, hold);
        }

        Instant checkoutDeadline = Objects.requireNonNull(
                hold.checkoutExpiresAt(), "pending booking hold must have checkout expiration");
        if (!clock.instant().isBefore(checkoutDeadline)) {
            PaymentIntentStatus cancelStatus = cancelPaymentOrReschedule(withIntent);
            if (cancelStatus == PaymentIntentStatus.SUCCEEDED) {
                return confirm(withIntent, hold);
            }
            if (cancelStatus == PaymentIntentStatus.FAILED || cancelStatus == PaymentIntentStatus.CANCELED) {
                return fail(withIntent, hold);
            }
        }

        return reschedule(withIntent);
    }

    private PaymentIntentStatus getPaymentStatusOrReschedule(Booking booking) {
        try {
            return paymentGateway.getPaymentStatus(booking.paymentIntentIdOptional().orElseThrow());
        } catch (RuntimeException providerFailure) {
            reschedule(booking);
            throw providerFailure;
        }
    }

    private PaymentIntentStatus cancelPaymentOrReschedule(Booking booking) {
        try {
            return paymentGateway.cancelPaymentIntent(booking.paymentIntentIdOptional().orElseThrow());
        } catch (RuntimeException providerFailure) {
            reschedule(booking);
            throw providerFailure;
        }
    }

    private Booking confirm(Booking booking, Hold hold) {
        Hold converted = hold.convert();
        Booking confirmed = booking.confirm();
        checkoutGateway.finalizeBooking(converted, confirmed);
        return confirmed;
    }

    private Booking fail(Booking booking, Hold hold) {
        Hold failedHold = hold.fail();
        Booking failedBooking = booking.fail();
        checkoutGateway.failBooking(failedHold, failedBooking);
        return failedBooking;
    }

    private Booking ensurePaymentIntent(Booking booking) {
        if (booking.paymentIntentIdOptional().isPresent()) return booking;
        PaymentIntent intent = paymentGateway.createPaymentIntent(booking.id(), booking.totalPrice(), booking.id().value());
        Booking withIntent = booking.attachPaymentIntent(intent.id());
        bookingRepository.savePaymentIntent(withIntent);
        return withIntent;
    }

    private Booking reschedule(Booking booking) {
        Instant nextAttempt = clock.instant().plus(pendingBackoff);
        if (!nextAttempt.isAfter(booking.nextReconcileAt())) {
            nextAttempt = booking.nextReconcileAt().plus(pendingBackoff);
        }
        Booking rescheduled = booking.rescheduleReconciliation(nextAttempt);
        bookingRepository.rescheduleReconciliation(rescheduled);
        return rescheduled;
    }

    private Hold loadHold(Booking booking) {
        return holdRepository.findById(booking.holdId())
                .orElseThrow(() -> new IllegalStateException("hold not found for booking: " + booking.id().value()));
    }
}
