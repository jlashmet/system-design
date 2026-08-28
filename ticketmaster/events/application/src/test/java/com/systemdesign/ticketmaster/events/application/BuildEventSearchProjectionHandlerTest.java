package com.systemdesign.ticketmaster.events.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.events.domain.Event;
import com.systemdesign.ticketmaster.events.domain.EventId;
import com.systemdesign.ticketmaster.events.domain.EventRepository;
import com.systemdesign.ticketmaster.events.domain.EventStatus;
import com.systemdesign.ticketmaster.events.domain.Venue;
import com.systemdesign.ticketmaster.events.domain.VenueId;
import com.systemdesign.ticketmaster.events.domain.VenueRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuildEventSearchProjectionHandlerTest {

    @Test
    void enrichesScheduledEventWithVenueMetadata() {
        EventId eventId = new EventId("event-42");
        VenueId venueId = new VenueId("venue-7");
        Event event = event(eventId, venueId, EventStatus.SCHEDULED);
        Venue venue = new Venue(venueId, "Hollywood Bowl", "Los Angeles");
        BuildEventSearchProjectionHandler handler = new BuildEventSearchProjectionHandler(
                id -> Optional.of(event),
                id -> Optional.of(venue));

        Optional<EventSearchProjectionAction> projection = handler.handle(new BuildEventSearchProjectionQuery(eventId));

        assertThat(projection).contains(new EventSearchProjection(
                "event-42",
                "The National",
                "Hollywood Bowl",
                "Los Angeles",
                Instant.parse("2026-10-20T03:00:00Z"),
                "CONCERT"));
    }

    @Test
    void cancelledEventProducesDeleteWithoutLoadingVenue() {
        EventId eventId = new EventId("event-42");
        VenueId venueId = new VenueId("venue-7");
        Event event = event(eventId, venueId, EventStatus.CANCELLED);
        VenueRepository venues = id -> {
            throw new AssertionError("cancelled event should not load venue metadata");
        };
        BuildEventSearchProjectionHandler handler = new BuildEventSearchProjectionHandler(
                id -> Optional.of(event),
                venues);

        Optional<EventSearchProjectionAction> projection = handler.handle(new BuildEventSearchProjectionQuery(eventId));

        assertThat(projection).contains(new DeleteEventSearchProjection("event-42"));
    }

    @Test
    void returnsEmptyWhenEventDoesNotExist() {
        EventRepository events = id -> Optional.empty();
        VenueRepository venues = id -> Optional.empty();
        BuildEventSearchProjectionHandler handler = new BuildEventSearchProjectionHandler(events, venues);

        Optional<EventSearchProjectionAction> projection = handler.handle(
                new BuildEventSearchProjectionQuery(new EventId("missing")));

        assertThat(projection).isEmpty();
    }

    private static Event event(EventId eventId, VenueId venueId, EventStatus status) {
        return new Event(
                eventId,
                "The National",
                venueId,
                Instant.parse("2026-10-20T03:00:00Z"),
                "CONCERT",
                status,
                "");
    }
}
