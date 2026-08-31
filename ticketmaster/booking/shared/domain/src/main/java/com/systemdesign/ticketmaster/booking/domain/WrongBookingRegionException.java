package com.systemdesign.ticketmaster.booking.domain;

import java.util.Objects;

public final class WrongBookingRegionException extends RuntimeException {
    private final EventId eventId;
    private final String localRegion;
    private final String ownerRegion;

    public WrongBookingRegionException(EventId eventId, String localRegion, String ownerRegion) {
        super("event " + Objects.requireNonNull(eventId, "eventId").value()
                + " is owned by " + requireText(ownerRegion, "ownerRegion")
                + ", not local region " + requireText(localRegion, "localRegion"));
        this.eventId = eventId;
        this.localRegion = localRegion;
        this.ownerRegion = ownerRegion;
    }

    public EventId eventId() { return eventId; }
    public String localRegion() { return localRegion; }
    public String ownerRegion() { return ownerRegion; }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
