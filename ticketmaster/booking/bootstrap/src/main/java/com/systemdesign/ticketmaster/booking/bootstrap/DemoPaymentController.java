package com.systemdesign.ticketmaster.booking.bootstrap;

import com.systemdesign.ticketmaster.booking.application.ReconcileBookingHandler;
import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DemoPaymentGateway;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only payment control. Production payment state must come from a verified provider
 * response/webhook; clients must never be allowed to mark their own payments successful.
 */
@RestController
@RequestMapping("/demo/bookings")
final class DemoPaymentController {
    private final DemoPaymentGateway paymentGateway;
    private final ReconcileBookingHandler reconcileBookingHandler;

    DemoPaymentController(
            DemoPaymentGateway paymentGateway,
            ReconcileBookingHandler reconcileBookingHandler) {
        this.paymentGateway = Objects.requireNonNull(paymentGateway, "paymentGateway");
        this.reconcileBookingHandler = Objects.requireNonNull(reconcileBookingHandler, "reconcileBookingHandler");
    }

    @PostMapping("/{bookingId}/payment-success")
    ResponseEntity<DemoPaymentResponse> succeed(@PathVariable String bookingId) {
        BookingId id = new BookingId(bookingId);
        paymentGateway.succeedPayment(id);
        Booking booking = reconcileBookingHandler.handle(id);
        DemoPaymentResponse body = new DemoPaymentResponse(
                booking.id().value(),
                booking.status().name(),
                booking.paymentIntentIdOptional().orElse(""));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    record DemoPaymentResponse(String bookingId, String status, String paymentIntentId) {
    }
}
