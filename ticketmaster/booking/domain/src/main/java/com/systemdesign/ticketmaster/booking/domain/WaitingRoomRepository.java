package com.systemdesign.ticketmaster.booking.domain;

import java.util.Optional;

public interface WaitingRoomRepository {
    WaitingRoomEntry join(WaitingRoomEntry entry);
    Optional<WaitingRoomEntry> findEntry(EventId eventId, UserId userId);
    Optional<EventAdmission> findAdmission(EventId eventId);

    /**
     * Creates the initial admission watermark if the event has not been enabled yet and
     * otherwise returns the existing watermark unchanged. Implementations that coordinate
     * multiple processes should make this create-if-absent operation atomic.
     */
    default EventAdmission initializeAdmission(EventAdmission initial) {
        return findAdmission(initial.eventId()).orElseGet(() -> advanceAdmission(initial));
    }

    EventAdmission advanceAdmission(EventAdmission admission);
}
