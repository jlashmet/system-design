package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.PK;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.number;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.string;

import com.systemdesign.ticketmaster.booking.checkout.infrastructure.BookingStorageUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingStatus;
import com.systemdesign.ticketmaster.booking.domain.CheckoutConflictException;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.EventOwnershipUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

public final class DynamoCheckoutGateway implements CheckoutGateway {
    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final String localRegion;

    public DynamoCheckoutGateway(DynamoDbClient dynamoDb, String tableName, String localRegion) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.localRegion = requireText(localRegion, "localRegion");
    }

    DynamoCheckoutGateway(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.localRegion = null;
    }

    @Override
    public void startCheckout(ReservationCheckout reservation, Booking pendingBooking) {
        requireStartState(reservation, pendingBooking);
        if (reservation.seatIds().size() > 96) {
            throw new IllegalArgumentException("checkout cannot contain more than 96 seats");
        }

        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(TransactWriteItem.builder().update(startHoldUpdate(reservation, pendingBooking)).build());
        for (SeatId seatId : reservation.seatIds()) {
            writes.add(TransactWriteItem.builder().update(startSeatUpdate(reservation, pendingBooking, seatId)).build());
        }
        writes.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(DynamoItemCodec.bookingToItem(pendingBooking))
                        .conditionExpression("attribute_not_exists(#pk)")
                        .expressionAttributeNames(Map.of("#pk", PK))
                        .build())
                .build());
        writes.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(DynamoItemCodec.idempotencyItem(pendingBooking))
                        .conditionExpression("attribute_not_exists(#pk)")
                        .expressionAttributeNames(Map.of("#pk", PK))
                        .build())
                .build());
        transact(reservation, writes);
    }

    @Override
    public void finalizeBooking(ReservationCheckout reservation, Booking confirmedBooking) {
        requireFinalState(reservation, confirmedBooking, BookingStatus.CONFIRMED);
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(TransactWriteItem.builder().update(terminalHoldUpdate(reservation, "CONVERTED")).build());
        writes.add(TransactWriteItem.builder().update(terminalBookingUpdate(confirmedBooking, "CONFIRMED")).build());
        for (SeatId seatId : reservation.seatIds()) {
            writes.add(TransactWriteItem.builder().update(bookSeatUpdate(reservation, confirmedBooking, seatId)).build());
        }
        transact(reservation, writes);
    }

    @Override
    public void failBooking(ReservationCheckout reservation, Booking failedBooking) {
        requireFinalState(reservation, failedBooking, BookingStatus.FAILED);
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(TransactWriteItem.builder().update(terminalHoldUpdate(reservation, "FAILED")).build());
        writes.add(TransactWriteItem.builder().update(terminalBookingUpdate(failedBooking, "FAILED")).build());
        for (SeatId seatId : reservation.seatIds()) {
            writes.add(TransactWriteItem.builder().update(releaseSeatUpdate(reservation, seatId)).build());
        }
        transact(reservation, writes);
    }

    private Update startHoldUpdate(ReservationCheckout reservation, Booking booking) {
        AttributeValue seatIds = AttributeValue.builder()
                .ss(reservation.seatIds().stream().map(SeatId::value).sorted().toList())
                .build();
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.holdPk(reservation.id()))))
                .updateExpression("SET #status = :checkout, #checkoutExpiresAt = :deadline")
                .conditionExpression("#entityType = :holdType AND #holdId = :holdId AND #eventId = :eventId "
                        + "AND #userId = :userId AND #seatIds = :seatIds "
                        + "AND #totalPriceAmount = :totalPriceAmount AND #totalPriceCurrency = :totalPriceCurrency "
                        + "AND #expiresAt = :expiresAt AND #expiresAt > :now AND #status = :active")
                .expressionAttributeNames(Map.of(
                        "#entityType", "entityType",
                        "#holdId", "holdId",
                        "#eventId", "eventId",
                        "#userId", "userId",
                        "#seatIds", "seatIds",
                        "#totalPriceAmount", "totalPriceAmount",
                        "#totalPriceCurrency", "totalPriceCurrency",
                        "#expiresAt", "expiresAt",
                        "#status", "status",
                        "#checkoutExpiresAt", "checkoutExpiresAt"))
                .expressionAttributeValues(Map.ofEntries(
                        Map.entry(":holdType", string("HOLD")),
                        Map.entry(":holdId", string(reservation.id().value())),
                        Map.entry(":eventId", string(reservation.eventId().value())),
                        Map.entry(":userId", string(reservation.userId().value())),
                        Map.entry(":seatIds", seatIds),
                        Map.entry(":totalPriceAmount", string(reservation.totalPrice().amount().toPlainString())),
                        Map.entry(":totalPriceCurrency", string(reservation.totalPrice().currency().getCurrencyCode())),
                        Map.entry(":expiresAt", number(reservation.expiresAt().toEpochMilli())),
                        Map.entry(":active", string("ACTIVE")),
                        Map.entry(":checkout", string("CHECKOUT_IN_PROGRESS")),
                        Map.entry(":deadline", number(reservation.checkoutExpiresAt().toEpochMilli())),
                        Map.entry(":now", number(booking.createdAt().toEpochMilli()))))
                .build();
    }

    private Update startSeatUpdate(ReservationCheckout reservation, Booking booking, SeatId seatId) {
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.seatPk(reservation.eventId(), seatId))))
                .updateExpression("SET #status = :checkout, #holdExpiresAt = :deadline")
                .conditionExpression("#entityType = :seatType AND #eventId = :eventId AND #seatId = :seatId "
                        + "AND #status = :held AND #holdId = :holdId AND #holdExpiresAt > :now")
                .expressionAttributeNames(Map.of(
                        "#entityType", "entityType",
                        "#eventId", "eventId",
                        "#seatId", "seatId",
                        "#status", "status",
                        "#holdId", "holdId",
                        "#holdExpiresAt", "holdExpiresAt"))
                .expressionAttributeValues(Map.of(
                        ":seatType", string("SEAT"),
                        ":eventId", string(reservation.eventId().value()),
                        ":seatId", string(seatId.value()),
                        ":checkout", string("CHECKOUT"),
                        ":held", string("HELD"),
                        ":holdId", string(reservation.id().value()),
                        ":deadline", number(reservation.checkoutExpiresAt().toEpochMilli()),
                        ":now", number(booking.createdAt().toEpochMilli())))
                .build();
    }

    private Update terminalHoldUpdate(ReservationCheckout reservation, String status) {
        AttributeValue seatIds = AttributeValue.builder()
                .ss(reservation.seatIds().stream().map(SeatId::value).sorted().toList())
                .build();
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.holdPk(reservation.id()))))
                .updateExpression("SET #status = :terminal")
                .conditionExpression("#entityType = :holdType AND #holdId = :holdId AND #eventId = :eventId "
                        + "AND #userId = :userId AND #seatIds = :seatIds "
                        + "AND #totalPriceAmount = :totalPriceAmount AND #totalPriceCurrency = :totalPriceCurrency "
                        + "AND #expiresAt = :expiresAt AND #checkoutExpiresAt = :checkoutExpiresAt "
                        + "AND #status = :checkout")
                .expressionAttributeNames(Map.of(
                        "#entityType", "entityType",
                        "#holdId", "holdId",
                        "#eventId", "eventId",
                        "#userId", "userId",
                        "#seatIds", "seatIds",
                        "#totalPriceAmount", "totalPriceAmount",
                        "#totalPriceCurrency", "totalPriceCurrency",
                        "#expiresAt", "expiresAt",
                        "#checkoutExpiresAt", "checkoutExpiresAt",
                        "#status", "status"))
                .expressionAttributeValues(Map.ofEntries(
                        Map.entry(":holdType", string("HOLD")),
                        Map.entry(":holdId", string(reservation.id().value())),
                        Map.entry(":eventId", string(reservation.eventId().value())),
                        Map.entry(":userId", string(reservation.userId().value())),
                        Map.entry(":seatIds", seatIds),
                        Map.entry(":totalPriceAmount", string(reservation.totalPrice().amount().toPlainString())),
                        Map.entry(":totalPriceCurrency", string(reservation.totalPrice().currency().getCurrencyCode())),
                        Map.entry(":expiresAt", number(reservation.expiresAt().toEpochMilli())),
                        Map.entry(":checkoutExpiresAt", number(reservation.checkoutExpiresAt().toEpochMilli())),
                        Map.entry(":checkout", string("CHECKOUT_IN_PROGRESS")),
                        Map.entry(":terminal", string(status))))
                .build();
    }

    private Update terminalBookingUpdate(Booking booking, String status) {
        String paymentIntentId = booking.paymentIntentIdOptional()
                .orElseThrow(() -> new IllegalArgumentException("terminal booking has no payment intent"));
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.bookingPk(booking.id()))))
                .updateExpression("SET #status = :terminal REMOVE #nextReconcileAt, #reconcileShard")
                .conditionExpression("#status = :pending AND #eventId = :eventId AND #holdId = :holdId "
                        + "AND #userId = :userId AND #checkoutIdempotencyKey = :checkoutIdempotencyKey "
                        + "AND #totalPriceAmount = :totalPriceAmount AND #totalPriceCurrency = :totalPriceCurrency "
                        + "AND #paymentIntentId = :paymentIntentId")
                .expressionAttributeNames(Map.of(
                        "#status", "status",
                        "#eventId", "eventId",
                        "#holdId", "holdId",
                        "#userId", "userId",
                        "#checkoutIdempotencyKey", "checkoutIdempotencyKey",
                        "#totalPriceAmount", "totalPriceAmount",
                        "#totalPriceCurrency", "totalPriceCurrency",
                        "#paymentIntentId", "paymentIntentId",
                        "#nextReconcileAt", "nextReconcileAt",
                        "#reconcileShard", "reconcileShard"))
                .expressionAttributeValues(Map.of(
                        ":terminal", string(status),
                        ":pending", string("PENDING_PAYMENT"),
                        ":eventId", string(booking.eventId().value()),
                        ":holdId", string(booking.holdId().value()),
                        ":userId", string(booking.userId().value()),
                        ":checkoutIdempotencyKey", string(booking.checkoutIdempotencyKey()),
                        ":totalPriceAmount", string(booking.totalPrice().amount().toPlainString()),
                        ":totalPriceCurrency", string(booking.totalPrice().currency().getCurrencyCode()),
                        ":paymentIntentId", string(paymentIntentId)))
                .build();
    }

    private Update bookSeatUpdate(ReservationCheckout reservation, Booking booking, SeatId seatId) {
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.seatPk(reservation.eventId(), seatId))))
                .updateExpression("SET #status = :booked, #bookingId = :bookingId REMOVE #holdExpiresAt")
                .conditionExpression("#entityType = :seatType AND #eventId = :eventId AND #seatId = :seatId "
                        + "AND #status = :checkout AND #holdId = :holdId")
                .expressionAttributeNames(Map.of(
                        "#entityType", "entityType",
                        "#eventId", "eventId",
                        "#seatId", "seatId",
                        "#status", "status",
                        "#bookingId", "bookingId",
                        "#holdExpiresAt", "holdExpiresAt",
                        "#holdId", "holdId"))
                .expressionAttributeValues(Map.of(
                        ":seatType", string("SEAT"),
                        ":eventId", string(reservation.eventId().value()),
                        ":seatId", string(seatId.value()),
                        ":booked", string("BOOKED"),
                        ":checkout", string("CHECKOUT"),
                        ":bookingId", string(booking.id().value()),
                        ":holdId", string(reservation.id().value())))
                .build();
    }

    private Update releaseSeatUpdate(ReservationCheckout reservation, SeatId seatId) {
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.seatPk(reservation.eventId(), seatId))))
                .updateExpression("SET #status = :available REMOVE #holdId, #holdExpiresAt, #bookingId")
                .conditionExpression("#entityType = :seatType AND #eventId = :eventId AND #seatId = :seatId "
                        + "AND #status = :checkout AND #holdId = :holdId")
                .expressionAttributeNames(Map.of(
                        "#entityType", "entityType",
                        "#eventId", "eventId",
                        "#seatId", "seatId",
                        "#status", "status",
                        "#holdId", "holdId",
                        "#holdExpiresAt", "holdExpiresAt",
                        "#bookingId", "bookingId"))
                .expressionAttributeValues(Map.of(
                        ":seatType", string("SEAT"),
                        ":eventId", string(reservation.eventId().value()),
                        ":seatId", string(seatId.value()),
                        ":available", string("AVAILABLE"),
                        ":checkout", string("CHECKOUT"),
                        ":holdId", string(reservation.id().value())))
                .build();
    }

    private void transact(ReservationCheckout reservation, List<TransactWriteItem> writes) {
        DynamoCheckoutEventFence.Fence fence = localRegion == null
                ? null
                : DynamoCheckoutEventFence.resolve(dynamoDb, tableName, reservation.eventId(), localRegion);
        if (fence != null) {
            writes.add(0, DynamoCheckoutEventFence.condition(tableName, reservation.eventId(), fence));
        }
        try {
            DynamoBookingCall.execute("checkout transaction", () -> dynamoDb.transactWriteItems(
                    TransactWriteItemsRequest.builder().transactItems(writes).build()));
        } catch (TransactionCanceledException e) {
            if (fence != null) assertFenceUnchanged(reservation, fence);
            if (DynamoTransactionCancellation.hasNonConditionalFailure(e)) {
                throw new BookingStorageUnavailableException("checkout transaction", e);
            }
            throw new CheckoutConflictException(reservation.id(), e);
        }
    }

    private void assertFenceUnchanged(ReservationCheckout reservation, DynamoCheckoutEventFence.Fence expected) {
        DynamoCheckoutEventFence.Fence current = DynamoCheckoutEventFence.resolve(
                dynamoDb, tableName, reservation.eventId(), localRegion);
        if (current.epoch() != expected.epoch()) {
            throw new EventOwnershipUnavailableException(
                    reservation.eventId(), "event write epoch changed during checkout transaction");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static void requireStartState(ReservationCheckout reservation, Booking booking) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(booking, "booking");
        if (reservation.status() != ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS
                || booking.status() != BookingStatus.PENDING_PAYMENT) {
            throw new IllegalArgumentException("checkout must start with checkout reservation and pending booking");
        }
        requireSameCheckoutScope(reservation, booking);
    }

    private static void requireFinalState(
            ReservationCheckout reservation, Booking booking, BookingStatus bookingStatus) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(booking, "booking");
        if (reservation.status() != ReservationCheckoutStatus.CHECKOUT_IN_PROGRESS
                || booking.status() != bookingStatus) {
            throw new IllegalArgumentException("terminal checkout state does not match requested operation");
        }
        requireSameCheckoutScope(reservation, booking);
    }

    private static void requireSameCheckoutScope(ReservationCheckout reservation, Booking booking) {
        if (!reservation.id().equals(booking.holdId())) {
            throw new IllegalArgumentException("booking does not belong to hold");
        }
        if (!reservation.eventId().equals(booking.eventId())) {
            throw new IllegalArgumentException("booking does not belong to hold event");
        }
        if (!reservation.userId().equals(booking.userId())) {
            throw new IllegalArgumentException("booking does not belong to hold user");
        }
        if (!reservation.totalPrice().equals(booking.totalPrice())) {
            throw new IllegalArgumentException("booking price does not match hold price");
        }
    }
}
