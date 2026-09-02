package com.systemdesign.ticketmaster.events.infrastructure.input;

import com.systemdesign.ticketmaster.events.infrastructure.common.EventsStorageUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.systemdesign.ticketmaster.events.infrastructure.input")
public final class EventsExceptionHandler {
    @ExceptionHandler(EventsStorageUnavailableException.class)
    ResponseEntity<ProblemDetail> storageUnavailable(EventsStorageUnavailableException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        detail.setTitle("Event metadata unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(detail);
    }
}
