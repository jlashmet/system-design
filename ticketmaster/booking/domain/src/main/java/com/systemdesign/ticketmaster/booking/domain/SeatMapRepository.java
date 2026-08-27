package com.systemdesign.ticketmaster.booking.domain;

import java.util.List;

public interface SeatMapRepository {
    void upsert(SeatMapSeat seat);
    List<SeatMapSeat> findSection(EventId eventId, SectionId sectionId);
}
