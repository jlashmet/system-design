package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingStatus;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

final class DynamoItemCodec {
    static final String PK = "pk";
    static final String STATE = "state";
    static final String STATE_TYPE = "type";
    static final String RECONCILIATION_INDEX = "reconciliation-index";

    private DynamoItemCodec() {
    }

    static Map<String, AttributeValue> bookingToItem(Booking booking) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, string(DynamoKeys.bookingPk(booking.id())));
        item.put("entityType", string("BOOKING"));
        item.put("bookingId", string(booking.id().value()));
        item.put("userId", string(booking.userId().value()));
        item.put("eventId", string(booking.eventId().value()));
        item.put("holdId", string(booking.holdId().value()));
        item.put(STATE, bookingState(booking.state()));
        item.put("totalPriceAmount", string(booking.totalPrice().amount().toPlainString()));
        item.put("totalPriceCurrency", string(booking.totalPrice().currency().getCurrencyCode()));
        item.put("checkoutIdempotencyKey", string(booking.checkoutIdempotencyKey()));
        item.put("createdAt", number(booking.createdAt().toEpochMilli()));
        materializeReconciliationIndex(booking, item);
        return item;
    }

    static Booking bookingFromItem(Map<String, AttributeValue> item) {
        return new Booking(
                new BookingId(item.get("bookingId").s()),
                new UserId(item.get("userId").s()),
                new EventId(item.get("eventId").s()),
                new HoldId(item.get("holdId").s()),
                bookingStateFromItem(item),
                new Price(new BigDecimal(item.get("totalPriceAmount").s()),
                        Currency.getInstance(item.get("totalPriceCurrency").s())),
                item.get("checkoutIdempotencyKey").s(),
                Instant.ofEpochMilli(Long.parseLong(item.get("createdAt").n())));
    }

    static AttributeValue bookingState(Booking.State state) {
        Map<String, AttributeValue> value = new HashMap<>();
        switch (state) {
            case Booking.PaymentIntentPending pending -> {
                value.put(STATE_TYPE, string("PAYMENT_INTENT_PENDING"));
                value.put("nextReconcileAt", number(pending.nextReconcileAt().toEpochMilli()));
                value.put("reconcileShard", number(pending.reconcileShard()));
            }
            case Booking.PaymentPending pending -> {
                value.put(STATE_TYPE, string("PAYMENT_PENDING"));
                value.put("paymentIntentId", string(pending.paymentIntentId()));
                value.put("nextReconcileAt", number(pending.nextReconcileAt().toEpochMilli()));
                value.put("reconcileShard", number(pending.reconcileShard()));
            }
            case Booking.Confirmed confirmed -> {
                value.put(STATE_TYPE, string("CONFIRMED"));
                value.put("paymentIntentId", string(confirmed.paymentIntentId()));
            }
            case Booking.FailedBeforePaymentIntent ignored ->
                    value.put(STATE_TYPE, string("FAILED_BEFORE_PAYMENT_INTENT"));
            case Booking.FailedAfterPaymentIntent failed -> {
                value.put(STATE_TYPE, string("FAILED_AFTER_PAYMENT_INTENT"));
                value.put("paymentIntentId", string(failed.paymentIntentId()));
            }
        }
        return AttributeValue.builder().m(value).build();
    }

    static void materializeReconciliationIndex(Booking booking, Map<String, AttributeValue> item) {
        if (booking.nextReconcileAt() != null && booking.reconcileShard() != null) {
            item.put("reconcileShard", string(DynamoKeys.reconciliationShard(booking.reconcileShard())));
            item.put("nextReconcileAt", number(booking.nextReconcileAt().toEpochMilli()));
        }
    }

    private static Booking.State bookingStateFromItem(Map<String, AttributeValue> item) {
        AttributeValue encodedState = item.get(STATE);
        if (encodedState != null && encodedState.m() != null && !encodedState.m().isEmpty()) {
            Map<String, AttributeValue> state = encodedState.m();
            return switch (requiredString(state, STATE_TYPE)) {
                case "PAYMENT_INTENT_PENDING" -> new Booking.PaymentIntentPending(
                        requiredInstant(state, "nextReconcileAt"), requiredInt(state, "reconcileShard"));
                case "PAYMENT_PENDING" -> new Booking.PaymentPending(
                        requiredString(state, "paymentIntentId"),
                        requiredInstant(state, "nextReconcileAt"), requiredInt(state, "reconcileShard"));
                case "CONFIRMED" -> new Booking.Confirmed(requiredString(state, "paymentIntentId"));
                case "FAILED_BEFORE_PAYMENT_INTENT" -> new Booking.FailedBeforePaymentIntent();
                case "FAILED_AFTER_PAYMENT_INTENT" ->
                        new Booking.FailedAfterPaymentIntent(requiredString(state, "paymentIntentId"));
                default -> throw new IllegalStateException("unsupported booking state: " + requiredString(state, STATE_TYPE));
            };
        }
        return legacyBookingState(item);
    }

    private static Booking.State legacyBookingState(Map<String, AttributeValue> item) {
        BookingStatus status = BookingStatus.valueOf(requiredString(item, "status"));
        String paymentIntentId = optionalString(item, "paymentIntentId");
        return switch (status) {
            case PENDING_PAYMENT -> paymentIntentId == null
                    ? new Booking.PaymentIntentPending(requiredTopLevelReconcileAt(item), requiredTopLevelReconcileShard(item))
                    : new Booking.PaymentPending(paymentIntentId, requiredTopLevelReconcileAt(item), requiredTopLevelReconcileShard(item));
            case CONFIRMED -> new Booking.Confirmed(requireLegacyPaymentIntent(paymentIntentId, status));
            case FAILED -> paymentIntentId == null
                    ? new Booking.FailedBeforePaymentIntent()
                    : new Booking.FailedAfterPaymentIntent(paymentIntentId);
        };
    }

    private static Instant requiredTopLevelReconcileAt(Map<String, AttributeValue> item) {
        AttributeValue value = item.get("nextReconcileAt");
        if (value == null || value.n() == null) throw new IllegalStateException("pending booking missing nextReconcileAt");
        return Instant.ofEpochMilli(Long.parseLong(value.n()));
    }

    private static int requiredTopLevelReconcileShard(Map<String, AttributeValue> item) {
        AttributeValue value = item.get("reconcileShard");
        if (value == null || value.s() == null) throw new IllegalStateException("pending booking missing reconcileShard");
        return DynamoKeys.reconciliationShardNumber(value.s());
    }

    private static String requireLegacyPaymentIntent(String paymentIntentId, BookingStatus status) {
        if (paymentIntentId == null) throw new IllegalStateException(status + " booking missing paymentIntentId");
        return paymentIntentId;
    }

    private static String requiredString(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.s() == null || value.s().isBlank()) {
            throw new IllegalStateException("missing string attribute: " + name);
        }
        return value.s();
    }

    private static String optionalString(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private static Instant requiredInstant(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.n() == null) throw new IllegalStateException("missing number attribute: " + name);
        return Instant.ofEpochMilli(Long.parseLong(value.n()));
    }

    private static int requiredInt(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.n() == null) throw new IllegalStateException("missing number attribute: " + name);
        return Integer.parseInt(value.n());
    }

    static Map<String, AttributeValue> idempotencyItem(Booking booking) {
        return Map.of(
                PK, string(DynamoKeys.idempotencyPk(
                        booking.eventId(), booking.holdId(), booking.checkoutIdempotencyKey())),
                "entityType", string("CHECKOUT_IDEMPOTENCY"),
                "bookingId", string(booking.id().value()),
                "eventId", string(booking.eventId().value()),
                "holdId", string(booking.holdId().value()),
                "idempotencyKey", string(booking.checkoutIdempotencyKey()));
    }

    static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
