package com.systemdesign.ticketmaster.events.application;

import com.systemdesign.ticketmaster.events.domain.Event;
import com.systemdesign.ticketmaster.events.domain.EventRepository;
import com.systemdesign.ticketmaster.events.domain.EventStatus;
import java.util.Objects;

public final class CreateEventHandler {
    private final EventRepository eventRepository;

    public CreateEventHandler(EventRepository eventRepository) {
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
    }

    public Event handle(CreateEventCommand command) {
        Objects.requireNonNull(command, "command");
        Event event = new Event(
                command.eventId(),
                command.name(),
                command.venueId(),
                command.startsAt(),
                command.category(),
                EventStatus.SCHEDULED,
                command.description());
        eventRepository.create(event);
        return event;
    }
}
