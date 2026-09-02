package com.systemdesign.ticketmaster.events.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.events.domain.EventAlreadyExistsException;
import com.systemdesign.ticketmaster.events.domain.EventId;
import com.systemdesign.ticketmaster.events.infrastructure.common.EventsStorageUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class EventsExceptionHandlerTest {
    private EventsExceptionHandler handler;
    private ResponseEntity<ProblemDetail> response;

    @Test
    void storageFailureIsRetryableServiceUnavailable() {
        handler = new EventsExceptionHandler();
        EventsStorageUnavailableException exception =
                new EventsStorageUnavailableException("event metadata read", new IllegalStateException("down"));

        response = handler.storageUnavailable(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Event metadata unavailable");
    }

    @Test
    void duplicateEventIdIsConflict() {
        handler = new EventsExceptionHandler();

        response = handler.eventAlreadyExists(new EventAlreadyExistsException(new EventId("event-1")));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Event already exists");
        assertThat(response.getBody().getDetail()).contains("event-1");
    }
}
