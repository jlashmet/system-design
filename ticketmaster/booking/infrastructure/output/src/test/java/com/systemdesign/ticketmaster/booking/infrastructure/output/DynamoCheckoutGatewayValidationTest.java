package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingStatus;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Currency;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class DynamoCheckoutGatewayValidationTest {
    private static final Instant NOW = Instant.parse("2026-08-31T17:00:00Z");
    private static final EventId HOLD_EVENT = new EventId("event-1");
    private static final EventId OTHER_EVENT = new EventId("event-2");
    private static final HoldId HOLD_ID = new HoldId("hold-1");
    private static final UserId USER_ID = new UserId("user-1");
    private static final Price PRICE = new Price(new BigDecimal("100.00"), Currency.getInstance("USD"));

    private DynamoDbClient dynamoDb;
    private DynamoCheckoutGateway gateway;
    private Hold checkoutHold;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://127.0.0.1:1"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        gateway = new DynamoCheckoutGateway(dynamoDb, "unused-table");
        checkoutHold = Hold.active(
                        HOLD_ID,
                        USER_ID,
                        HOLD_EVENT,
                        Set.of(new SeatId("A10")),
                        PRICE,
                        NOW,
                        NOW.plusSeconds(300))
                .startCheckout(NOW.plusSeconds(10), NOW.plusSeconds(120));
    }

    @AfterEach
    void tearDown() {
        dynamoDb.close();
    }

    @Test
    void startCheckoutRejectsBookingFromDifferentEventBeforeStorageMutation() {
        Booking booking = bookingFor(OTHER_EVENT, BookingStatus.PENDING_PAYMENT);

        assertThatThrownBy(() -> gateway.startCheckout(checkoutHold, booking))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("booking does not belong to hold event");
    }

    @Test
    void finalizeBookingRejectsBookingFromDifferentEventBeforeStorageMutation() {
        Booking booking = bookingFor(OTHER_EVENT, BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> gateway.finalizeBooking(checkoutHold.convert(), booking))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("booking does not belong to hold event");
    }

    @Test
    void failBookingRejectsBookingFromDifferentEventBeforeStorageMutation() {
        Booking booking = bookingFor(OTHER_EVENT, BookingStatus.FAILED);

        assertThatThrownBy(() -> gateway.failBooking(checkoutHold.fail(), booking))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("booking does not belong to hold event");
    }

    private Booking bookingFor(EventId eventId, BookingStatus status) {
        Instant nextReconcileAt = status == BookingStatus.PENDING_PAYMENT ? NOW.plusSeconds(60) : null;
        Integer reconcileShard = status == BookingStatus.PENDING_PAYMENT ? 1 : null;
        return new Booking(
                new BookingId("booking-1"),
                USER_ID,
                eventId,
                HOLD_ID,
                status,
                PRICE,
                "checkout-1",
                "pi-1",
                nextReconcileAt,
                reconcileShard,
                NOW.plusSeconds(10));
    }
}
