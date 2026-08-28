package com.systemdesign.ticketmaster.booking.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventOwnershipUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyConflictException;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.HoldOwnershipException;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import org.junit.jupiter.api.Test;
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
        thenExpectConflict();
    }

    @Test
    void checkoutForAnotherUsersHoldIsForbidden() {
        givenHoldOwnershipViolation();
        whenExceptionIsHandled();
        thenExpectForbidden();
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

    private void whenExceptionIsHandled() {
        if (exception instanceof WrongBookingRegionException wrongRegion) {
            response = handler.wrongBookingRegion(wrongRegion);
        } else if (exception instanceof EventOwnershipUnavailableException unavailable) {
            response = handler.bookingOwnershipUnavailable(unavailable);
        } else if (exception instanceof HoldOwnershipException ownership) {
            response = handler.holdOwnership(ownership);
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

    private void thenExpectConflict() {
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Booking conflict");
    }

    private void thenExpectForbidden() {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Hold access forbidden");
    }
}
