package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.PK;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.number;
import static com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoItemCodec.string;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingStatus;
import com.systemdesign.ticketmaster.booking.domain.CheckoutConflictException;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldStatus;
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
    public void startCheckout(Hold checkoutHold, Booking pendingBooking) {
        requireStartState(checkoutHold, pendingBooking);
        if (checkoutHold.seatIds().size() > 97) {
            throw new IllegalArgumentException("checkout cannot contain more than 97 seats");
        }

        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(TransactWriteItem.builder().update(startHoldUpdate(checkoutHold, pendingBooking)).build());
        for (SeatId seatId : checkoutHold.seatIds()) {
            writes.add(TransactWriteItem.builder().update(startSeatUpdate(checkoutHold, pendingBooking, seatId)).build());
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
        transact(checkoutHold, writes);
    }

    @Override
    public void finalizeBooking(Hold convertedHold, Booking confirmedBooking) {
        requireFinalState(convertedHold, confirmedBooking, HoldStatus.CONVERTED, BookingStatus.CONFIRMED);
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(TransactWriteItem.builder().update(terminalHoldUpdate(convertedHold, "CONVERTED")).build());
        writes.add(TransactWriteItem.builder().update(terminalBookingUpdate(confirmedBooking, "CONFIRMED")).build());
        for (SeatId seatId : convertedHold.seatIds()) {
            writes.add(TransactWriteItem.builder().update(bookSeatUpdate(convertedHold, confirmedBooking, seatId)).build());
        }
        transact(convertedHold, writes);
    }

    @Override
    public void failBooking(Hold failedHold, Booking failedBooking) {
        requireFinalState(failedHold, failedBooking, HoldStatus.FAILED, BookingStatus.FAILED);
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(TransactWriteItem.builder().update(terminalHoldUpdate(failedHold, "FAILED")).build());
        writes.add(TransactWriteItem.builder().update(terminalBookingUpdate(failedBooking, "FAILED")).build());
        for (SeatId seatId : failedHold.seatIds()) {
            writes.add(TransactWriteItem.builder().update(releaseSeatUpdate(failedHold, seatId)).build());
        }
        transact(failedHold, writes);
    }

    private Update startHoldUpdate(Hold hold, Booking booking) {
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.holdPk(hold.id()))))
                .updateExpression("SET #status = :checkout, #checkoutExpiresAt = :deadline")
                .conditionExpression("#status = :active AND #expiresAt > :now")
                .expressionAttributeNames(Map.of(
                        "#status", "status",
                        "#checkoutExpiresAt", "checkoutExpiresAt",
                        "#expiresAt", "expiresAt"))
                .expressionAttributeValues(Map.of(
                        ":checkout", string("CHECKOUT_IN_PROGRESS"),
                        ":active", string("ACTIVE"),
                        ":deadline", number(hold.checkoutExpiresAt().toEpochMilli()),
                        ":now", number(booking.createdAt().toEpochMilli())))
                .build();
    }

    private Update startSeatUpdate(Hold hold, Booking booking, SeatId seatId) {
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.seatPk(hold.eventId(), seatId))))
                .updateExpression("SET #status = :checkout, #holdExpiresAt = :deadline")
                .conditionExpression("#status = :held AND #holdId = :holdId AND #holdExpiresAt > :now")
                .expressionAttributeNames(Map.of(
                        "#status", "status",
                        "#holdId", "holdId",
                        "#holdExpiresAt", "holdExpiresAt"))
                .expressionAttributeValues(Map.of(
                        ":checkout", string("CHECKOUT"),
                        ":held", string("HELD"),
                        ":holdId", string(hold.id().value()),
                        ":deadline", number(hold.checkoutExpiresAt().toEpochMilli()),
                        ":now", number(booking.createdAt().toEpochMilli())))
                .build();
    }

    private Update terminalHoldUpdate(Hold hold, String status) {
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.holdPk(hold.id()))))
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

    private Update bookSeatUpdate(Hold hold, Booking booking, SeatId seatId) {
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.seatPk(hold.eventId(), seatId))))
                .updateExpression("SET #status = :booked, #bookingId = :bookingId REMOVE #holdExpiresAt")
                .conditionExpression("#status = :checkout AND #holdId = :holdId")
                .expressionAttributeNames(Map.of(
                        "#status", "status",
                        "#bookingId", "bookingId",
                        "#holdExpiresAt", "holdExpiresAt",
                        "#holdId", "holdId"))
                .expressionAttributeValues(Map.of(
                        ":booked", string("BOOKED"),
                        ":checkout", string("CHECKOUT"),
                        ":bookingId", string(booking.id().value()),
                        ":holdId", string(hold.id().value())))
                .build();
    }

    private Update releaseSeatUpdate(Hold hold, SeatId seatId) {
        return Update.builder()
                .tableName(tableName)
                .key(Map.of(PK, string(DynamoKeys.seatPk(hold.eventId(), seatId))))
                .updateExpression("SET #status = :available REMOVE #holdId, #holdExpiresAt, #bookingId")
                .conditionExpression("#status = :checkout AND #holdId = :holdId")
                .expressionAttributeNames(Map.of(
                        "#status", "status",
                        "#holdId", "holdId",
                        "#holdExpiresAt", "holdExpiresAt",
                        "#bookingId", "bookingId"))
                .expressionAttributeValues(Map.of(
                        ":available", string("AVAILABLE"),
                        ":checkout", string("CHECKOUT"),
                        ":holdId", string(hold.id().value())))
                .build();
    }

    private void transact(Hold hold, List<TransactWriteItem> writes) {
        try {
            DynamoBookingCall.execute("checkout transaction", () -> dynamoDb.transactWriteItems(
                    TransactWriteItemsRequest.builder().transactItems(writes).build()));
        } catch (TransactionCanceledException e) {
            if (DynamoTransactionCancellation.hasNonConditionalFailure(e)) {
                throw new BookingStorageUnavailableException("checkout transaction", e);
            }
            throw new CheckoutConflictException(hold.id(), e);
        }
    }

    private static void requireStartState(Hold hold, Booking booking) {
        Objects.requireNonNull(hold, "hold");
        Objects.requireNonNull(booking, "booking");
        if (hold.status() != HoldStatus.CHECKOUT_IN_PROGRESS || booking.status() != BookingStatus.PENDING_PAYMENT) {
            throw new IllegalArgumentException("checkout must start with checkout hold and pending booking");
        }
        requireSameCheckoutScope(hold, booking);
    }

    private static void requireFinalState(Hold hold, Booking booking, HoldStatus holdStatus, BookingStatus bookingStatus) {
        Objects.requireNonNull(hold, "hold");
        Objects.requireNonNull(booking, "booking");
        if (hold.status() != holdStatus || booking.status() != bookingStatus) {
            throw new IllegalArgumentException("terminal checkout state does not match requested operation");
        }
        requireSameCheckoutScope(hold, booking);
    }

    private static void requireSameCheckoutScope(Hold hold, Booking booking) {
        if (!hold.id().equals(booking.holdId())) {
            throw new IllegalArgumentException("booking does not belong to hold");
        }
        if (!hold.eventId().equals(booking.eventId())) {
            throw new IllegalArgumentException("booking does not belong to hold event");
        }
    }
}
