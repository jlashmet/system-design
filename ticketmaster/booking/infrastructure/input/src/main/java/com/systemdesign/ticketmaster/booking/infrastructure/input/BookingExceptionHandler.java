package com.systemdesign.ticketmaster.booking.infrastructure.input;

import com.systemdesign.ticketmaster.booking.domain.AdmissionRequiredException;
import com.systemdesign.ticketmaster.booking.domain.CheckoutConflictException;
import com.systemdesign.ticketmaster.booking.domain.EventOwnershipUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyConflictException;
import com.systemdesign.ticketmaster.booking.domain.HoldOwnershipException;
import com.systemdesign.ticketmaster.booking.domain.SeatClaimConflictException;
import com.systemdesign.ticketmaster.booking.domain.SeatUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomDisabledException;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import com.systemdesign.ticketmaster.booking.infrastructure.common.BookingStorageUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.systemdesign.ticketmaster.booking.infrastructure.input")
public final class BookingExceptionHandler {

    static final String BOOKING_REGION_HEADER = "X-Booking-Region";

    @ExceptionHandler(AdmissionRequiredException.class)
    ResponseEntity<ProblemDetail> admissionRequired(AdmissionRequiredException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
        detail.setTitle("Waiting-room admission required");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(detail);
    }

    @ExceptionHandler(HoldOwnershipException.class)
    ResponseEntity<ProblemDetail> holdOwnership(HoldOwnershipException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
        detail.setTitle("Hold access forbidden");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(detail);
    }

    @ExceptionHandler(WaitingRoomDisabledException.class)
    ResponseEntity<ProblemDetail> waitingRoomDisabled(WaitingRoomDisabledException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        detail.setTitle("Waiting room disabled");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
    }

    @ExceptionHandler(WrongBookingRegionException.class)
    ResponseEntity<ProblemDetail> wrongBookingRegion(WrongBookingRegionException exception) {
        HttpStatus status = HttpStatus.valueOf(421);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        detail.setTitle("Misdirected booking request");
        detail.setProperty("eventId", exception.eventId().value());
        detail.setProperty("ownerRegion", exception.ownerRegion());
        HttpHeaders headers = new HttpHeaders();
        headers.set(BOOKING_REGION_HEADER, exception.ownerRegion());
        return new ResponseEntity<>(detail, headers, status);
    }

    @ExceptionHandler(EventOwnershipUnavailableException.class)
    ResponseEntity<ProblemDetail> bookingOwnershipUnavailable(EventOwnershipUnavailableException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        detail.setTitle("Booking ownership unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(detail);
    }

    @ExceptionHandler(BookingStorageUnavailableException.class)
    ResponseEntity<ProblemDetail> bookingStorageUnavailable(BookingStorageUnavailableException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        detail.setTitle("Booking storage unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(detail);
    }

    @ExceptionHandler({SeatClaimConflictException.class, SeatUnavailableException.class,
            CheckoutConflictException.class, HoldIdempotencyConflictException.class})
    ResponseEntity<ProblemDetail> conflict(RuntimeException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        detail.setTitle("Booking conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle("Invalid request");
        return ResponseEntity.badRequest().body(detail);
    }
}
