package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import com.systemdesign.ticketmaster.booking.domain.Price;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Development-only payment adapter used to make the interview project runnable without
 * implying that a production payment provider has been integrated.
 */
public final class DemoPaymentGateway implements PaymentGateway {
    private static final String INTENT_PREFIX = "demo_pi_";

    private final ConcurrentMap<String, PaymentIntent> intentsByIdempotencyKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PaymentIntentStatus> statusByIntentId = new ConcurrentHashMap<>();

    @Override
    public PaymentIntent createPaymentIntent(BookingId bookingId, Price price, String idempotencyKey) {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");

        return intentsByIdempotencyKey.computeIfAbsent(idempotencyKey, ignored -> {
            PaymentIntent intent = new PaymentIntent(
                    intentId(bookingId),
                    PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
            statusByIntentId.put(intent.id(), intent.status());
            return intent;
        });
    }

    @Override
    public PaymentIntentStatus getPaymentStatus(String paymentIntentId) {
        Objects.requireNonNull(paymentIntentId, "paymentIntentId");
        PaymentIntentStatus status = statusByIntentId.get(paymentIntentId);
        if (status == null) throw new IllegalArgumentException("unknown demo payment intent: " + paymentIntentId);
        return status;
    }

    @Override
    public PaymentIntentStatus cancelPaymentIntent(String paymentIntentId) {
        Objects.requireNonNull(paymentIntentId, "paymentIntentId");
        return statusByIntentId.compute(paymentIntentId, (ignored, current) -> {
            if (current == null) throw new IllegalArgumentException("unknown demo payment intent: " + paymentIntentId);
            if (current == PaymentIntentStatus.SUCCEEDED
                    || current == PaymentIntentStatus.FAILED
                    || current == PaymentIntentStatus.CANCELED) {
                return current;
            }
            return PaymentIntentStatus.CANCELED;
        });
    }

    /**
     * Development control used only by the disabled-by-default demo endpoint. A production adapter
     * learns this transition from its payment provider, not from a Ticketmaster client request.
     */
    public PaymentIntentStatus succeedPayment(BookingId bookingId) {
        Objects.requireNonNull(bookingId, "bookingId");
        String paymentIntentId = intentId(bookingId);
        return statusByIntentId.compute(paymentIntentId, (ignored, current) -> {
            if (current == null) throw new IllegalArgumentException("unknown demo payment intent: " + paymentIntentId);
            if (current == PaymentIntentStatus.CANCELED || current == PaymentIntentStatus.FAILED) {
                throw new IllegalStateException("demo payment intent is already terminal: " + current);
            }
            return PaymentIntentStatus.SUCCEEDED;
        });
    }

    private static String intentId(BookingId bookingId) {
        return INTENT_PREFIX + bookingId.value();
    }
}
