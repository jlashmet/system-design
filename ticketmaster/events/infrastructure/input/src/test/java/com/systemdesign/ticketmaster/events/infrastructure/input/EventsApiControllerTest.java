package com.systemdesign.ticketmaster.events.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.events.api.model.EventResponse;
import com.systemdesign.ticketmaster.events.application.GetEventHandler;
import com.systemdesign.ticketmaster.events.domain.Event;
import com.systemdesign.ticketmaster.events.domain.EventId;
import com.systemdesign.ticketmaster.events.domain.EventRepository;
import com.systemdesign.ticketmaster.events.domain.EventStatus;
import com.systemdesign.ticketmaster.events.domain.VenueId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class EventsApiControllerTest {

    @Test
    void mapsDomainEventToGeneratedResponse() {
        EventRepository repository = eventId -> Optional.of(new Event(
                eventId,
                "Taylor Swift",
                new VenueId("venue-1"),
                Instant.parse("2026-10-10T03:00:00Z"),
                "CONCERT",
                EventStatus.SCHEDULED,
                "Opening night"));
        EventsApiController controller = new EventsApiController(new GetEventHandler(repository));

        ResponseEntity<EventResponse> response = controller.getEvent("event-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getEventId()).isEqualTo("event-1");
        assertThat(response.getBody().getName()).isEqualTo("Taylor Swift");
        assertThat(response.getBody().getVenueId()).isEqualTo("venue-1");
        assertThat(response.getBody().getStartsAt().toInstant()).isEqualTo(Instant.parse("2026-10-10T03:00:00Z"));
        assertThat(response.getBody().getStatus()).isEqualTo("SCHEDULED");
    }

    @Test
    void returnsNotFoundWhenEventDoesNotExist() {
        EventsApiController controller = new EventsApiController(new GetEventHandler(eventId -> Optional.empty()));

        ResponseEntity<EventResponse> response = controller.getEvent("missing");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNull();
    }
}
