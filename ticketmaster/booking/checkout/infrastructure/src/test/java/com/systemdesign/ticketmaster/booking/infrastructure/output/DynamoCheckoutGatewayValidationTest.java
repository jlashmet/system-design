package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.BookingStatus;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.PreparedCheckout;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.ReservationCheckout;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
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
    private static final SeatId SEAT_ID = new SeatId("A10");
    private static final Price PRICE = new Price(new BigDecimal("100.00"), Currency.getInstance("USD"));

    private DynamoDbClient dynamoDb;
    private DynamoCheckoutGateway gateway;
    private ReservationCheckout checkoutReservation;
    private PreparedCheckout preparedCheckout;

    @BeforeEach
    void setUp() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://127.0.0.1:1"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        gateway = new DynamoCheckoutGateway(dynamoDb, "unused-table");
        Hold checkoutHold = Hold.checkout(
                HOLD_ID,
                USER_ID,
                HOLD_EVENT,
                Set.of(SEAT_ID),
                PRICE,
                NOW.plusSeconds(10),
                NOW.plusSeconds(120));
        checkoutReservation = ReservationTestFixtures.from(checkoutHold);
        preparedCheckout = new PreparedCheckout(checkoutReservation, Map.of(SEAT_ID, PRICE));
    }

    @AfterEach
    void tearDown() {
        dynamoDb.close();
    }

    @Test
    void startCheckoutRejectsBookingFromDifferentEventBeforeStorageMutation() {
        Booking booking = bookingFor(OTHER_EVENT, USER_ID, PRICE, BookingStatus.PENDING_PAYMENT);

        assertThatThrownBy(() -> gateway.startCheckout(preparedCheckout, booking))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("booking does not belong to reservation event");
    }

    @Test
    void startCheckoutRejectsBookingFromDifferentUserBeforeStorageMutation() {
        Booking booking = bookingFor(HOLD_EVENT, new UserId("user-2"), PRICE, BookingStatus.PENDING_PAYMENT);

        assertThatThrownBy(() -> gateway.startCheckout(preparedCheckout, booking))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("booking does not belong to reservation user");
    }

    @Test
    void startCheckoutRejectsBookingWithDifferentPriceBeforeStorageMutation() {
        Price differentPrice = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));
        Booking booking = bookingFor(HOLD_EVENT, USER_ID, differentPrice, BookingStatus.PENDING_PAYMENT);

        assertThatThrownBy(() -> gateway.startCheckout(preparedCheckout, booking))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("booking price does not match reservation price");
    }

    @Test
    void finalizeBookingRejectsBookingFromDifferentEventBeforeStorageMutation() {
        Booking booking = bookingFor(OTHER_EVENT, USER_ID, PRICE, BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> gateway.finalizeBooking(checkoutReservation, booking))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("booking does not belong to reservation event");
    }

    @Test
    void failBookingRejectsBookingFromDifferentEventBeforeStorageMutation() {
        Booking booking = bookingFor(OTHER_EVENT, USER_ID, PRICE, BookingStatus.FAILED);

        assertThatThrownBy(() -> gateway.failBooking(checkoutReservation, booking))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("booking does not belong to reservation event");
    }

    private Booking bookingFor(EventId eventId, UserId userId, Price price, BookingStatus status) {
        Instant nextReconcileAt = status == BookingStatus.PENDING_PAYMENT ? NOW.plusSeconds(60) : null;
        Integer reconcileShard = status == BookingStatus.PENDING_PAYMENT ? 1 : null;
        return new Booking(
                new BookingId("booking-1"),
                userId,
                eventId,
                HOLD_ID,
                status,
                price,
                "checkout-1",
                "pi-1",
                nextReconcileAt,
                reconcileShard,
                NOW.plusSeconds(10));
    }
}
