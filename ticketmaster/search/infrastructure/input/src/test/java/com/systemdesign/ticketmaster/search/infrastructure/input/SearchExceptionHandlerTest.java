package com.systemdesign.ticketmaster.search.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.search.domain.SearchUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class SearchExceptionHandlerTest {
    private final SearchExceptionHandler handler = new SearchExceptionHandler();

    @Test
    void mapsSearchBackendFailureToServiceUnavailable() {
        ResponseEntity<ProblemDetail> response = handler.unavailable(
                new SearchUnavailableException("event search is temporarily unavailable", new RuntimeException("boom")));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Search temporarily unavailable");
    }

    @Test
    void mapsInvalidCursorToBadRequest() {
        ResponseEntity<ProblemDetail> response = handler.badRequest(new IllegalArgumentException("invalid search cursor"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Invalid search request");
    }
}
