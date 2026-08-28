package com.systemdesign.ticketmaster.search.infrastructure.input;

import com.systemdesign.ticketmaster.search.domain.SearchUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.systemdesign.ticketmaster.search.infrastructure.input")
public final class SearchExceptionHandler {

    @ExceptionHandler(SearchUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(SearchUnavailableException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        detail.setTitle("Search temporarily unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle("Invalid search request");
        return ResponseEntity.badRequest().body(detail);
    }
}
