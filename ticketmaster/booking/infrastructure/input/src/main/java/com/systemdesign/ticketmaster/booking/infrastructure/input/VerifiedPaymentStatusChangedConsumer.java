package com.systemdesign.ticketmaster.booking.infrastructure.input;

import com.systemdesign.ticketmaster.booking.application.ReconcileBookingHandler;
import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Trusted input boundary for a provider-specific webhook/message adapter after that adapter has
 * authenticated and validated the provider event.
 *
 * <p>No payment status is accepted from the integration message. The Booking application re-reads
 * authoritative provider state through PaymentGateway before confirming or releasing inventory.</p>
 */
@Component
public final class VerifiedPaymentStatusChangedConsumer {
    private final ReconcileBookingHandler reconcileBookingHandler;

    public VerifiedPaymentStatusChangedConsumer(ReconcileBookingHandler reconcileBookingHandler) {
        this.reconcileBookingHandler = Objects.requireNonNull(reconcileBookingHandler, "reconcileBookingHandler");
    }

    public Booking accept(String eventId, String bookingId) {
        return reconcileBookingHandler.handle(new EventId(eventId), new BookingId(bookingId));
    }
}
