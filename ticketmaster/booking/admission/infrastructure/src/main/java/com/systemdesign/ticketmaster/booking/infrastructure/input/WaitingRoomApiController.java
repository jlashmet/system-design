package com.systemdesign.ticketmaster.booking.infrastructure.input;

import com.systemdesign.ticketmaster.booking.api.WaitingRoomApi;
import com.systemdesign.ticketmaster.booking.api.model.AdmissionStatusResponse;
import com.systemdesign.ticketmaster.booking.api.model.WaitingRoomEntryResponse;
import com.systemdesign.ticketmaster.booking.application.AdmissionDecision;
import com.systemdesign.ticketmaster.booking.application.CheckAdmissionHandler;
import com.systemdesign.ticketmaster.booking.application.CheckAdmissionQuery;
import com.systemdesign.ticketmaster.booking.application.JoinWaitingRoomCommand;
import com.systemdesign.ticketmaster.booking.application.JoinWaitingRoomHandler;
import com.systemdesign.ticketmaster.booking.domain.AdmissionGrant;
import com.systemdesign.ticketmaster.booking.domain.AdmissionGrantService;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class WaitingRoomApiController implements WaitingRoomApi {
    private static final String NO_STORE = "no-store";

    private final JoinWaitingRoomHandler joinWaitingRoomHandler;
    private final CheckAdmissionHandler checkAdmissionHandler;
    private final AdmissionGrantService admissionGrantService;
    private final Clock clock;

    public WaitingRoomApiController(
            JoinWaitingRoomHandler joinWaitingRoomHandler,
            CheckAdmissionHandler checkAdmissionHandler,
            AdmissionGrantService admissionGrantService,
            Clock clock) {
        this.joinWaitingRoomHandler = Objects.requireNonNull(joinWaitingRoomHandler, "joinWaitingRoomHandler");
        this.checkAdmissionHandler = Objects.requireNonNull(checkAdmissionHandler, "checkAdmissionHandler");
        this.admissionGrantService = Objects.requireNonNull(admissionGrantService, "admissionGrantService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ResponseEntity<WaitingRoomEntryResponse> joinWaitingRoom(String eventId, String userId) {
        WaitingRoomEntry entry = joinWaitingRoomHandler.handle(
                new JoinWaitingRoomCommand(new EventId(eventId), new UserId(userId)));

        WaitingRoomEntryResponse response = new WaitingRoomEntryResponse();
        response.setEventId(entry.eventId().value());
        response.setUserId(entry.userId().value());
        response.setJoinedAt(entry.joinedAt().atOffset(ZoneOffset.UTC));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(response);
    }

    @Override
    public ResponseEntity<AdmissionStatusResponse> getAdmissionStatus(String eventId, String userId) {
        EventId requestedEventId = new EventId(eventId);
        UserId requestedUserId = new UserId(userId);
        AdmissionDecision decision = checkAdmissionHandler.handle(
                new CheckAdmissionQuery(requestedEventId, requestedUserId));

        AdmissionStatusResponse response = new AdmissionStatusResponse();
        response.setStatus(AdmissionStatusResponse.StatusEnum.fromValue(decision.name()));
        if (decision == AdmissionDecision.ADMITTED) {
            issueGrantBestEffort(requestedEventId, requestedUserId, response);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(response);
    }

    private void issueGrantBestEffort(
            EventId eventId,
            UserId userId,
            AdmissionStatusResponse response) {
        try {
            admissionGrantService.issue(eventId, userId, clock.instant()).ifPresent(grant -> applyGrant(response, grant));
        } catch (RuntimeException ignored) {
            // Grant issuance is an optimization. The admitted status remains usable and hold
            // creation can fall back to authoritative waiting-room reads.
        }
    }

    private static void applyGrant(AdmissionStatusResponse response, AdmissionGrant grant) {
        response.setAdmissionToken(grant.token());
        response.setAdmissionTokenExpiresAt(grant.expiresAt().atOffset(ZoneOffset.UTC));
    }
}
