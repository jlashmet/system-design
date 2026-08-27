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
        item.put("status", string(booking.status().name()));
        item.put("totalPriceAmount", string(booking.totalPrice().amount().toPlainString()));
        item.put("totalPriceCurrency", string(booking.totalPrice().currency().getCurrencyCode()));
        item.put("checkoutIdempotencyKey", string(booking.checkoutIdempotencyKey()));
        item.put("createdAt", number(booking.createdAt().toEpochMilli()));
        if (booking.paymentIntentId() != null) {
            item.put("paymentIntentId", string(booking.paymentIntentId()));
        }
        if (booking.nextReconcileAt() != null && booking.reconcileShard() != null) {
            item.put("reconcileShard", string(DynamoKeys.reconciliationShard(booking.reconcileShard())));
            item.put("nextReconcileAt", number(booking.nextReconcileAt().toEpochMilli()));
        }
        return item;
    }

    static Booking bookingFromItem(Map<String, AttributeValue> item) {
        String paymentIntentId = item.containsKey("paymentIntentId") ? item.get("paymentIntentId").s() : null;
        Instant nextReconcileAt = item.containsKey("nextReconcileAt")
                ? Instant.ofEpochMilli(Long.parseLong(item.get("nextReconcileAt").n()))
                : null;
        Integer reconcileShard = item.containsKey("reconcileShard")
                ? DynamoKeys.reconciliationShardNumber(item.get("reconcileShard").s())
                : null;
        return new Booking(
                new BookingId(item.get("bookingId").s()),
                new UserId(item.get("userId").s()),
                new EventId(item.get("eventId").s()),
                new HoldId(item.get("holdId").s()),
                BookingStatus.valueOf(item.get("status").s()),
                new Price(new BigDecimal(item.get("totalPriceAmount").s()),
                        Currency.getInstance(item.get("totalPriceCurrency").s())),
                item.get("checkoutIdempotencyKey").s(),
                paymentIntentId,
                nextReconcileAt,
                reconcileShard,
                Instant.ofEpochMilli(Long.parseLong(item.get("createdAt").n())));
    }

    static Map<String, AttributeValue> idempotencyItem(Booking booking) {
        return Map.of(
                PK, string(DynamoKeys.idempotencyPk(booking.checkoutIdempotencyKey())),
                "entityType", string("CHECKOUT_IDEMPOTENCY"),
                "bookingId", string(booking.id().value()),
                "idempotencyKey", string(booking.checkoutIdempotencyKey()));
    }

    static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
