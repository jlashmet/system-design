package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.util.Objects;

public record JoinWaitingRoomCommand(EventId eventId, UserId userId) {
    public JoinWaitingRoomCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
    }
}
