package com.systemdesign.ticketmaster.search.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.search.domain.SearchUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class SearchExceptionHandlerTest {
    private SearchExceptionHandler handler;
    private RuntimeException exception;
    private ResponseEntity<ProblemDetail> response;

    @Test
    void mapsSearchBackendFailureToRetryableServiceUnavailable() {
        givenSearchBackendFailure();
        whenExceptionIsHandled();
        thenExpectRetryableServiceUnavailable();
    }

    @Test
    void mapsInvalidCursorToBadRequest() {
        givenInvalidSearchRequest();
        whenExceptionIsHandled();
        thenExpectBadRequest();
    }

    private void givenSearchBackendFailure() {
        handler = new SearchExceptionHandler();
        exception = new SearchUnavailableException(
                "event search is temporarily unavailable", new RuntimeException("boom"));
        response = null;
    }

    private void givenInvalidSearchRequest() {
        handler = new SearchExceptionHandler();
        exception = new IllegalArgumentException("invalid search cursor");
        response = null;
    }

    private void whenExceptionIsHandled() {
        if (exception instanceof SearchUnavailableException unavailable) {
            response = handler.unavailable(unavailable);
        } else {
            response = handler.badRequest((IllegalArgumentException) exception);
        }
    }

    private void thenExpectRetryableServiceUnavailable() {
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Search temporarily unavailable");
    }

    private void thenExpectBadRequest() {
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Invalid search request");
    }
}
