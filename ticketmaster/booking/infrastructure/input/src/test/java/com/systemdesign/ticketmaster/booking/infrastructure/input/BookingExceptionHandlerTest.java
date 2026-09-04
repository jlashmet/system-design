package com.systemdesign.ticketmaster.booking.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.checkout.infrastructure.BookingStorageUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.CheckoutExpiredException;
import com.systemdesign.ticketmaster.booking.domain.CheckoutIdempotencyConflictException;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventOwnershipUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldNotFoundException;
import com.systemdesign.ticketmaster.booking.domain.HoldOwnershipException;
import com.systemdesign.ticketmaster.booking.domain.PaymentProviderUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomDisabledException;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntryNotFoundException;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class BookingExceptionHandlerTest {
    private final BookingExceptionHandler handler = new BookingExceptionHandler();
    private RuntimeException exception;
    private ResponseEntity<ProblemDetail> response;

    @Test
    void wrongRegionReturnsRerouteHint() {
        exception = new WrongBookingRegionException(new EventId("event-123"), "us-west-2", "us-east-1");
        whenExceptionIsHandled();
        assertThat(response.getStatusCode().value()).isEqualTo(421);
        assertThat(response.getHeaders().getFirst(BookingExceptionHandler.BOOKING_REGION_HEADER)).isEqualTo("us-east-1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("eventId", "event-123");
        assertThat(response.getBody().getProperties()).containsEntry("ownerRegion", "us-east-1");
    }

    @Test
    void unavailableOwnershipRemainsServiceUnavailable() {
        exception = new EventOwnershipUnavailableException(new EventId("event-123"), "control plane unavailable");
        whenExceptionIsHandled();
        assertThat(response.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void reusedCheckoutIdempotencyKeyForDifferentSeatsIsConflict() {
        exception = new CheckoutIdempotencyConflictException("request-123");
        whenExceptionIsHandled();
        thenExpectConflict("Booking conflict");
    }

    @Test
    void checkoutReservationForAnotherUserIsForbidden() {
        exception = new HoldOwnershipException(new HoldId("checkout-1"), new UserId("user-other"));
        whenExceptionIsHandled();
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Checkout reservation access forbidden");
    }

    @Test
    void missingCheckoutReservationIsNotFound() {
        exception = new HoldNotFoundException(new HoldId("checkout-missing"));
        whenExceptionIsHandled();
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Checkout reservation not found");
    }

    @Test
    void expiredCheckoutIsConflict() {
        exception = new CheckoutExpiredException(new HoldId("checkout-expired"));
        whenExceptionIsHandled();
        thenExpectConflict("Checkout expired");
    }

    @Test
    void missingWaitingRoomEntryIsNotFound() {
        exception = new WaitingRoomEntryNotFoundException(new EventId("event-123"), new UserId("user-missing"));
        whenExceptionIsHandled();
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Waiting-room entry not found");
    }

    @Test
    void joiningDisabledWaitingRoomIsConflict() {
        exception = new WaitingRoomDisabledException(new EventId("event-123"));
        whenExceptionIsHandled();
        thenExpectConflict("Waiting room disabled");
    }

    @Test
    void transientBookingStorageFailureIsRetryableServiceUnavailable() {
        exception = new BookingStorageUnavailableException("checkout transaction", new IllegalStateException("throttled"));
        whenExceptionIsHandled();
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Booking storage unavailable");
    }

    @Test
    void transientPaymentProviderFailureIsRetryableServiceUnavailable() {
        exception = new PaymentProviderUnavailableException(
                "payment intent creation", new IllegalStateException("provider timeout"));
        whenExceptionIsHandled();
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Payment provider unavailable");
        assertThat(response.getBody().getProperties()).containsEntry("operation", "payment intent creation");
    }

    @Test
    void invalidPaymentWebhookAuthenticationIsUnauthorized() {
        exception = new PaymentWebhookAuthenticationException();
        whenExceptionIsHandled();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Payment webhook authentication failed");
    }

    private void whenExceptionIsHandled() {
        if (exception instanceof PaymentWebhookAuthenticationException authentication) {
            response = handler.paymentWebhookAuthentication(authentication);
        } else if (exception instanceof WrongBookingRegionException wrongRegion) {
            response = handler.wrongBookingRegion(wrongRegion);
        } else if (exception instanceof EventOwnershipUnavailableException unavailable) {
            response = handler.bookingOwnershipUnavailable(unavailable);
        } else if (exception instanceof HoldOwnershipException ownership) {
            response = handler.holdOwnership(ownership);
        } else if (exception instanceof HoldNotFoundException notFound) {
            response = handler.holdNotFound(notFound);
        } else if (exception instanceof CheckoutExpiredException expired) {
            response = handler.checkoutExpired(expired);
        } else if (exception instanceof WaitingRoomEntryNotFoundException notFound) {
            response = handler.waitingRoomEntryNotFound(notFound);
        } else if (exception instanceof WaitingRoomDisabledException disabled) {
            response = handler.waitingRoomDisabled(disabled);
        } else if (exception instanceof BookingStorageUnavailableException storageUnavailable) {
            response = handler.bookingStorageUnavailable(storageUnavailable);
        } else if (exception instanceof PaymentProviderUnavailableException providerUnavailable) {
            response = handler.paymentProviderUnavailable(providerUnavailable);
        } else {
            response = handler.conflict(exception);
        }
    }

    private void thenExpectConflict(String title) {
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo(title);
    }
}
