package com.systemdesign.ticketmaster.events.domain;

import java.util.Optional;

public interface VenueRepository {
    Optional<Venue> findById(VenueId venueId);
}
