package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.SeatId;

final class DynamoReservationKeys {
    private DynamoReservationKeys() {
    }

    static String eventOwnershipPk(EventId eventId) {
        return "EVENT#" + eventId.value() + "#OWNERSHIP";
    }

    static String seatPk(EventId eventId, SeatId seatId) {
        return "EVENT#" + eventId.value() + "#SEAT#" + seatId.value();
    }

    static String holdPk(HoldId holdId) {
        return "HOLD#" + holdId.value();
    }
}
