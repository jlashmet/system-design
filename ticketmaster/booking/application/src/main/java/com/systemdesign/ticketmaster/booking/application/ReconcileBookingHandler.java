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
        PaymentIntentStatus paymentStatus;
        try {
            paymentStatus = paymentGateway.getPaymentStatus(withIntent.paymentIntentIdOptional().orElseThrow());
        } catch (RuntimeException providerFailure) {
            reschedule(withIntent);
            throw providerFailure;
        }

        if (paymentStatus == PaymentIntentStatus.SUCCEEDED) {
            Hold hold = loadHold(withIntent).convert();
            Booking confirmed = withIntent.confirm();
            checkoutGateway.finalizeBooking(hold, confirmed);
            return confirmed;
        }
        if (paymentStatus == PaymentIntentStatus.FAILED || paymentStatus == PaymentIntentStatus.CANCELED) {
            Hold hold = loadHold(withIntent).fail();
            Booking failed = withIntent.fail();
            checkoutGateway.failBooking(hold, failed);
            return failed;
        }
        return reschedule(withIntent);
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
