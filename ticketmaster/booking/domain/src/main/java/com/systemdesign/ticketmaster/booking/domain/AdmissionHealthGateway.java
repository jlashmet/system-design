package com.systemdesign.ticketmaster.booking.domain;

public interface AdmissionHealthGateway {
    AdmissionCapacity assess(EventId eventId);
}
