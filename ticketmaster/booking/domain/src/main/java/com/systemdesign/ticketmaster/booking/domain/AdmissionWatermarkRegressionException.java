package com.systemdesign.ticketmaster.booking.domain;

public final class AdmissionWatermarkRegressionException extends RuntimeException {
    public AdmissionWatermarkRegressionException(EventId eventId) {
        super("admission watermark cannot move backward for event " + eventId.value());
    }
}
