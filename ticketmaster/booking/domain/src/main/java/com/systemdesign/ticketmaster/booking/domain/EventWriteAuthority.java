package com.systemdesign.ticketmaster.booking.domain;

public interface EventWriteAuthority {
    void assertMayWrite(EventId eventId);
}
