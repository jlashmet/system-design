package com.systemdesign.ticketmaster.events.infrastructure.input;

import com.systemdesign.ticketmaster.events.api.EventsApi;
import com.systemdesign.ticketmaster.events.api.model.EventResponse;
import com.systemdesign.ticketmaster.events.application.GetEventHandler;
import com.systemdesign.ticketmaster.events.application.GetEventQuery;
import com.systemdesign.ticketmaster.events.domain.Event;
import com.systemdesign.ticketmaster.events.domain.EventId;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class EventsApiController implements EventsApi {
    private final GetEventHandler getEventHandler;

    public EventsApiController(GetEventHandler getEventHandler) {
        this.getEventHandler = getEventHandler;
    }

    @Override
    public ResponseEntity<EventResponse> getEvent(String eventId) {
        return getEventHandler.handle(new GetEventQuery(new EventId(eventId)))
                .map(EventsApiController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static EventResponse toResponse(Event event) {
        EventResponse response = new EventResponse();
        response.setEventId(event.id().value());
        response.setName(event.name());
        response.setVenueId(event.venueId().value());
        response.setStartsAt(event.startsAt().atOffset(ZoneOffset.UTC));
        response.setCategory(event.category());
        response.setStatus(event.status().name());
        response.setDescription(event.description());
        return response;
    }
}
