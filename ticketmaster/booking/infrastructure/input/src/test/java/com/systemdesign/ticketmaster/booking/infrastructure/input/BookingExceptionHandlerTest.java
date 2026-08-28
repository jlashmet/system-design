package com.systemdesign.ticketmaster.booking.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventOwnershipUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyConflictException;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class BookingExceptionHandlerTest {
    private final BookingExceptionHandler handler = new BookingExceptionHandler();

    @Test
    void wrongRegionReturnsRerouteHint() {
        WrongBookingRegionException exception = new WrongBookingRegionException(
                new EventId("event-123"), "us-west-2", "us-east-1");
        ResponseEntity<ProblemDetail> response = handler.wrongBookingRegion(exception);
        assertThat(response.getStatusCode().value()).isEqualTo(421);
        assertThat(response.getHeaders().getFirst(BookingExceptionHandler.BOOKING_REGION_HEADER)).isEqualTo("us-east-1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("eventId", "event-123");
        assertThat(response.getBody().getProperties()).containsEntry("ownerRegion", "us-east-1");
    }

    @Test
    void unavailableOwnershipRemainsServiceUnavailable() {
        EventOwnershipUnavailableException exception = new EventOwnershipUnavailableException(
                new EventId("event-123"), "control plane unavailable");
        ResponseEntity<ProblemDetail> response = handler.bookingOwnershipUnavailable(exception);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders()).doesNotContainKey(BookingExceptionHandler.BOOKING_REGION_HEADER);
    }

    @Test
    void reusedHoldIdempotencyKeyForDifferentRequestIsConflict() {
        ResponseEntity<ProblemDetail> response = handler.conflict(
                new HoldIdempotencyConflictException(new HoldIdempotencyKey("request-123")));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Booking conflict");
    }
}
