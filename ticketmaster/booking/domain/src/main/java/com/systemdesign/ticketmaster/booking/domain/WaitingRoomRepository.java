package com.systemdesign.ticketmaster.booking.domain;

import java.util.Optional;

public interface WaitingRoomRepository {
    WaitingRoomEntry join(WaitingRoomEntry entry);
    Optional<WaitingRoomEntry> findEntry(EventId eventId, UserId userId);
    Optional<EventAdmission> findAdmission(EventId eventId);
    EventAdmission advanceAdmission(EventAdmission admission);
}
