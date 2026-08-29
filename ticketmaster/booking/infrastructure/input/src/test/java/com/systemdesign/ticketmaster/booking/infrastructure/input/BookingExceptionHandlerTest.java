package com.systemdesign.ticketmaster.booking.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventOwnershipUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyConflictException;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.HoldNotFoundException;
import com.systemdesign.ticketmaster.booking.domain.HoldOwnershipException;
import com.systemdesign.ticketmaster.booking.domain.PaymentProviderUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomDisabledException;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntryNotFoundException;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import com.systemdesign.ticketmaster.booking.infrastructure.common.BookingStorageUnavailableException;
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
        givenWrongRegion();
        whenExceptionIsHandled();
        thenExpectRerouteHint();
    }

    @Test
    void unavailableOwnershipRemainsServiceUnavailable() {
        givenOwnershipUnavailable();
        whenExceptionIsHandled();
        thenExpectServiceUnavailable();
    }

    @Test
    void reusedHoldIdempotencyKeyForDifferentRequestIsConflict() {
        givenHoldIdempotencyConflict();
        whenExceptionIsHandled();
        thenExpectConflict("Booking conflict");
    }

    @Test
    void checkoutForAnotherUsersHoldIsForbidden() {
        givenHoldOwnershipViolation();
        whenExceptionIsHandled();
        thenExpectForbidden();
    }

    @Test
    void missingHoldIsNotFound() {
        givenHoldNotFound();
        whenExceptionIsHandled();
        thenExpectNotFound("Hold not found");
    }

    @Test
    void missingWaitingRoomEntryIsNotFound() {
        givenWaitingRoomEntryNotFound();
        whenExceptionIsHandled();
        thenExpectNotFound("Waiting-room entry not found");
    }

    @Test
    void joiningDisabledWaitingRoomIsConflict() {
        givenWaitingRoomDisabled();
        whenExceptionIsHandled();
        thenExpectConflict("Waiting room disabled");
    }

    @Test
    void transientBookingStorageFailureIsRetryableServiceUnavailable() {
        givenBookingStorageUnavailable();
        whenExceptionIsHandled();
        thenExpectRetryableStorageUnavailable();
    }

    @Test
    void transientPaymentProviderFailureIsRetryableServiceUnavailable() {
        givenPaymentProviderUnavailable();
        whenExceptionIsHandled();
        thenExpectRetryablePaymentProviderUnavailable("payment intent creation");
    }

    @Test
    void invalidPaymentWebhookAuthenticationIsUnauthorized() {
        givenPaymentWebhookAuthenticationFailure();
        whenExceptionIsHandled();
        thenExpectUnauthorizedWebhook();
    }

    private void givenWrongRegion() {
        exception = new WrongBookingRegionException(
                new EventId("event-123"), "us-west-2", "us-east-1");
        response = null;
    }

    private void givenOwnershipUnavailable() {
        exception = new EventOwnershipUnavailableException(
                new EventId("event-123"), "control plane unavailable");
        response = null;
    }

    private void givenHoldIdempotencyConflict() {
        exception = new HoldIdempotencyConflictException(new HoldIdempotencyKey("request-123"));
        response = null;
    }

    private void givenHoldOwnershipViolation() {
        exception = new HoldOwnershipException(new HoldId("hold-1"), new UserId("user-other"));
        response = null;
    }

    private void givenHoldNotFound() {
        exception = new HoldNotFoundException(new HoldId("hold-missing"));
        response = null;
    }

    private void givenWaitingRoomEntryNotFound() {
        exception = new WaitingRoomEntryNotFoundException(
                new EventId("event-123"), new UserId("user-missing"));
        response = null;
    }

    private void givenWaitingRoomDisabled() {
        exception = new WaitingRoomDisabledException(new EventId("event-123"));
        response = null;
    }

    private void givenBookingStorageUnavailable() {
        exception = new BookingStorageUnavailableException("seat claim", new IllegalStateException("throttled"));
        response = null;
    }

    private void givenPaymentProviderUnavailable() {
        exception = new PaymentProviderUnavailableException(
                "payment intent creation", new IllegalStateException("provider timeout"));
        response = null;
    }

    private void givenPaymentWebhookAuthenticationFailure() {
        exception = new PaymentWebhookAuthenticationException();
        response = null;
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

    private void thenExpectRerouteHint() {
        assertThat(response.getStatusCode().value()).isEqualTo(421);
        assertThat(response.getHeaders().getFirst(BookingExceptionHandler.BOOKING_REGION_HEADER)).isEqualTo("us-east-1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("eventId", "event-123");
        assertThat(response.getBody().getProperties()).containsEntry("ownerRegion", "us-east-1");
    }

    private void thenExpectServiceUnavailable() {
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders()).doesNotContainKey(BookingExceptionHandler.BOOKING_REGION_HEADER);
    }

    private void thenExpectConflict(String title) {
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo(title);
    }

    private void thenExpectForbidden() {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Hold access forbidden");
    }

    private void thenExpectNotFound(String title) {
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo(title);
    }

    private void thenExpectRetryableStorageUnavailable() {
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Booking storage unavailable");
    }

    private void thenExpectRetryablePaymentProviderUnavailable(String operation) {
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Payment provider unavailable");
        assertThat(response.getBody().getProperties()).containsEntry("operation", operation);
    }

    private void thenExpectUnauthorizedWebhook() {
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Payment webhook authentication failed");
    }
}
