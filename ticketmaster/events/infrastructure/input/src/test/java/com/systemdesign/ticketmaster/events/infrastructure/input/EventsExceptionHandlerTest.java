package com.systemdesign.ticketmaster.events.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.events.infrastructure.common.EventsStorageUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class EventsExceptionHandlerTest {
    private EventsExceptionHandler handler;
    private EventsStorageUnavailableException exception;
    private ResponseEntity<ProblemDetail> response;

    @Test
    void storageFailureIsRetryableServiceUnavailable() {
        givenStorageFailure();
        whenExceptionIsHandled();
        thenExpectRetryableServiceUnavailable();
    }

    private void givenStorageFailure() {
        handler = new EventsExceptionHandler();
        exception = new EventsStorageUnavailableException("event metadata read", new IllegalStateException("down"));
        response = null;
    }

    private void whenExceptionIsHandled() {
        response = handler.storageUnavailable(exception);
    }

    private void thenExpectRetryableServiceUnavailable() {
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Event metadata unavailable");
    }
}
