package com.systemdesign.ticketmaster.booking.domain;

import java.util.List;

public interface SeatMapRepository {
    void upsert(SeatMapSeat seat);
    List<SectionId> findSections(EventId eventId);
    List<SeatMapSeat> findSection(EventId eventId, SectionId sectionId);
}
