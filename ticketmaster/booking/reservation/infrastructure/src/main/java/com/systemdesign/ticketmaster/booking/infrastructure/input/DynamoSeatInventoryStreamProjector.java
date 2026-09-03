package com.systemdesign.ticketmaster.booking.infrastructure.input;

import com.systemdesign.ticketmaster.booking.application.ProjectSeatMapHandler;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SeatStatus;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;

public final class DynamoSeatInventoryStreamProjector {
    private static final String STATE = "state";

    private final ProjectSeatMapHandler handler;

    public DynamoSeatInventoryStreamProjector(ProjectSeatMapHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public void project(StreamRecord record) {
        Objects.requireNonNull(record, "record");
        if (!record.hasNewImage()) return;
        Map<String, AttributeValue> image = record.newImage();
        if (!"SEAT".equals(string(image, "entityType"))) return;
        SeatProjectionState state = seatState(image);
        handler.handle(new SeatMapSeat(
                new EventId(string(image, "eventId")),
                new SectionId(string(image, "sectionId")),
                new SeatId(string(image, "seatId")),
                string(image, "row"),
                string(image, "number"),
                new Price(new BigDecimal(string(image, "priceAmount")),
                        Currency.getInstance(string(image, "priceCurrency"))),
                state.status(),
                state.holdExpiresAt()));
    }

    private static SeatProjectionState seatState(Map<String, AttributeValue> image) {
        AttributeValue encodedState = image.get(STATE);
        if (encodedState != null && encodedState.m() != null && !encodedState.m().isEmpty()) {
            Map<String, AttributeValue> state = encodedState.m();
            SeatStatus status = SeatStatus.valueOf(string(state, "type"));
            Instant holdExpiresAt = switch (status) {
                case HELD, CHECKOUT -> requiredInstant(state, "expiresAt");
                case AVAILABLE, BOOKED -> null;
            };
            return new SeatProjectionState(status, holdExpiresAt);
        }
        return new SeatProjectionState(
                SeatStatus.valueOf(string(image, "status")),
                optionalInstant(image, "holdExpiresAt"));
    }

    private static String string(Map<String, AttributeValue> image, String name) {
        AttributeValue value = image.get(name);
        if (value == null || value.s() == null || value.s().isBlank()) {
            throw new IllegalArgumentException("seat stream image is missing " + name);
        }
        return value.s();
    }

    private static Instant requiredInstant(Map<String, AttributeValue> image, String name) {
        AttributeValue value = image.get(name);
        if (value == null || value.n() == null || value.n().isBlank()) {
            throw new IllegalArgumentException("seat stream state is missing " + name);
        }
        return Instant.ofEpochMilli(Long.parseLong(value.n()));
    }

    private static Instant optionalInstant(Map<String, AttributeValue> image, String name) {
        AttributeValue value = image.get(name);
        if (value == null || value.n() == null || value.n().isBlank()) return null;
        return Instant.ofEpochMilli(Long.parseLong(value.n()));
    }

    private record SeatProjectionState(SeatStatus status, Instant holdExpiresAt) {
    }
}
