package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.PK;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.number;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.string;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingStatus;
import com.systemdesign.ticketmaster.booking.domain.CheckoutConflictException;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckoutStatus;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.infrastructure.common.BookingStorageUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

public final class DynamoCheckoutGateway implements CheckoutGateway {
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoCheckoutGateway(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
    }

    @Override
    public void startCheckout(ReservationCheckout reservation, Booking pendingBooking) {
        requireStartState(reservation, pendingBooking);
        if (reservation.seatIds().size() > 97) {
            throw new IllegalArgumentException("checkout cannot contain more than 97 seats");
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
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.holdPk(reservation.id()))))
                .updateExpression("SET #status = :checkout, #checkoutExpiresAt = :deadline")
                .conditionExpression("#status = :active AND #expiresAt > :now")
                .expressionAttributeNames(Map.of(
                        "#status", "status",
                        "#checkoutExpiresAt", "checkoutExpiresAt",
                        "#expiresAt", "expiresAt"))
                .expressionAttributeValues(Map.of(
                        ":checkout", string("CHECKOUT_IN_PROGRESS"),
                        ":active", string("ACTIVE"),
                        ":deadline", number(reservation.checkoutExpiresAt().toEpochMilli()),
                        ":now", number(booking.createdAt().toEpochMilli())))
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
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.holdPk(reservation.id()))))
                .updateExpression("SET #status = :terminal")
                .conditionExpression("#status = :checkout")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":terminal", string(status),
                        ":checkout", string("CHECKOUT_IN_PROGRESS")))
                .build();
    }

    private Update terminalBookingUpdate(Booking booking, String status) {
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.bookingPk(booking.id()))))
                .updateExpression("SET #status = :terminal REMOVE #nextReconcileAt, #reconcileShard")
                .conditionExpression("#status = :pending AND #holdId = :holdId")
                .expressionAttributeNames(Map.of(
                        "#status", "status",
                        "#holdId", "holdId",
                        "#nextReconcileAt", "nextReconcileAt",
                        "#reconcileShard", "reconcileShard"))
                .expressionAttributeValues(Map.of(
                        ":terminal", string(status),
                        ":pending", string("PENDING_PAYMENT"),
                        ":holdId", string(booking.holdId().value())))
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
        try {
            DynamoBookingCall.execute("checkout transaction", () -> dynamoDb.transactWriteItems(
                    TransactWriteItemsRequest.builder().transactItems(writes).build()));
        } catch (TransactionCanceledException e) {
            if (DynamoTransactionCancellation.hasNonConditionalFailure(e)) {
                throw new BookingStorageUnavailableException("checkout transaction", e);
            }
            throw new CheckoutConflictException(reservation.id(), e);
        }
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
