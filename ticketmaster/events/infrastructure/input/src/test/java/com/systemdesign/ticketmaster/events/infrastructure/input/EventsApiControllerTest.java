package com.systemdesign.ticketmaster.events.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.events.api.model.CreateEventRequest;
import com.systemdesign.ticketmaster.events.api.model.EventResponse;
import com.systemdesign.ticketmaster.events.application.CreateEventHandler;
import com.systemdesign.ticketmaster.events.application.GetEventHandler;
import com.systemdesign.ticketmaster.events.domain.Event;
import com.systemdesign.ticketmaster.events.domain.EventRepository;
import com.systemdesign.ticketmaster.events.domain.EventStatus;
import com.systemdesign.ticketmaster.events.domain.VenueId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class EventsApiControllerTest {
    private EventsApiController controller;
    private ResponseEntity<EventResponse> response;

    @Test
    void mapsDomainEventToGeneratedResponseAndMarksMetadataCacheable() {
        givenExistingEvent();
        whenEventIsRequested("event-1");
        thenExpectCacheableEventResponse();
    }

    @Test
    void returnsNotFoundWithoutCachingWhenEventDoesNotExist() {
        givenMissingEvent();
        whenEventIsRequested("missing");
        thenExpectUncachedNotFound();
    }

    @Test
    void createsScheduledEventAndReturnsCanonicalLocation() {
        AtomicReference<Event> created = new AtomicReference<>();
        controller = new EventsApiController(
                new CreateEventHandler(created::set),
                new GetEventHandler(eventId -> Optional.empty()));
        CreateEventRequest request = new CreateEventRequest();
        request.setEventId("event-new");
        request.setName("Opening Night");
        request.setVenueId("venue-9");
        request.setStartsAt(OffsetDateTime.ofInstant(Instant.parse("2026-11-01T03:30:00Z"), ZoneOffset.UTC));
        request.setCategory("CONCERT");
        request.setDescription("Tour opener");

        response = controller.createEvent(request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getLocation()).hasToString("/events/event-new");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo("SCHEDULED");
        assertThat(created.get()).isNotNull();
        assertThat(created.get().id().value()).isEqualTo("event-new");
        assertThat(created.get().venueId().value()).isEqualTo("venue-9");
        assertThat(created.get().startsAt()).isEqualTo(Instant.parse("2026-11-01T03:30:00Z"));
        assertThat(created.get().status()).isEqualTo(EventStatus.SCHEDULED);
    }

    private void givenExistingEvent() {
        EventRepository repository = eventId -> Optional.of(new Event(
                eventId,
                "Taylor Swift",
                new VenueId("venue-1"),
                Instant.parse("2026-10-10T03:00:00Z"),
                "CONCERT",
                EventStatus.SCHEDULED,
                "Opening night"));
        controller = new EventsApiController(new CreateEventHandler(event -> {}), new GetEventHandler(repository));
        response = null;
    }

    private void givenMissingEvent() {
        controller = new EventsApiController(
                new CreateEventHandler(event -> {}),
                new GetEventHandler(eventId -> Optional.empty()));
        response = null;
    }

    private void whenEventIsRequested(String eventId) {
        response = controller.getEvent(eventId);
    }

    private void thenExpectCacheableEventResponse() {
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getEventId()).isEqualTo("event-1");
        assertThat(response.getBody().getName()).isEqualTo("Taylor Swift");
        assertThat(response.getBody().getVenueId()).isEqualTo("venue-1");
        assertThat(response.getBody().getStartsAt().toInstant()).isEqualTo(Instant.parse("2026-10-10T03:00:00Z"));
        assertThat(response.getBody().getStatus()).isEqualTo("SCHEDULED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("public, max-age=60, stale-while-revalidate=300, stale-if-error=300");
    }

    private void thenExpectUncachedNotFound() {
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isNull();
    }
}
