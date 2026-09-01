package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.AdmissionAccess;
import com.systemdesign.ticketmaster.booking.domain.AdmissionGrantService;
import com.systemdesign.ticketmaster.booking.domain.AdmissionRequiredException;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.time.Instant;
import java.util.Objects;

/** Admission-owned implementation of the narrow Reservation-facing admission contract. */
public final class AdmissionAccessService implements AdmissionAccess {
    private final WaitingRoomRepository waitingRoomRepository;
    private final AdmissionGrantService admissionGrantService;

    public AdmissionAccessService(WaitingRoomRepository waitingRoomRepository,
                                  AdmissionGrantService admissionGrantService) {
        this.waitingRoomRepository = Objects.requireNonNull(waitingRoomRepository, "waitingRoomRepository");
        this.admissionGrantService = Objects.requireNonNull(admissionGrantService, "admissionGrantService");
    }

    @Override
    public void requireAdmission(EventId eventId, UserId userId, String admissionToken, Instant now) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(now, "now");
        if (acceptsAdmissionGrant(eventId, userId, admissionToken, now)) return;
        waitingRoomRepository.findAdmission(eventId).ifPresent(admission -> {
            WaitingRoomEntry entry = waitingRoomRepository.findEntry(eventId, userId)
                    .orElseThrow(() -> new AdmissionRequiredException(eventId, userId));
            if (!admission.admits(entry)) throw new AdmissionRequiredException(eventId, userId);
        });
    }

    private boolean acceptsAdmissionGrant(EventId eventId, UserId userId, String admissionToken, Instant now) {
        if (admissionToken == null) return false;
        try {
            return admissionGrantService.accepts(eventId, userId, admissionToken, now);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
