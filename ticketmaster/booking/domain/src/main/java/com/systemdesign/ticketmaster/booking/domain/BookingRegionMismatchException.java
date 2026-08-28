package com.systemdesign.ticketmaster.booking.domain;

public final class BookingRegionMismatchException extends RuntimeException {
    public BookingRegionMismatchException(EventId eventId, String localRegion, String ownerRegion, long epoch) {
        super("event " + eventId.value() + " is owned by " + ownerRegion
                + " at epoch " + epoch + "; local region is " + localRegion);
    }

    public BookingRegionMismatchException(EventId eventId, String localRegion) {
        super("event " + eventId.value() + " has no authoritative booking owner; local region is " + localRegion);
    }
}
