package com.systemdesign.ticketmaster.events.domain;

public interface EventWriter {
    void create(Event event);
}
