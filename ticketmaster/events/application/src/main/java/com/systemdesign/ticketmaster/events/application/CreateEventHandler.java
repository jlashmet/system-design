package com.systemdesign.ticketmaster.events.application;

import com.systemdesign.ticketmaster.events.domain.Event;
import com.systemdesign.ticketmaster.events.domain.EventStatus;
import com.systemdesign.ticketmaster.events.domain.EventWriter;
import java.util.Objects;

public final class CreateEventHandler {
    private final EventWriter eventWriter;

    public CreateEventHandler(EventWriter eventWriter) {
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
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
        eventWriter.create(event);
        return event;
    }
}
