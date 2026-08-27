package com.systemdesign.ticketmaster.booking.infrastructure.input;

import com.systemdesign.ticketmaster.booking.api.WaitingRoomApi;
import com.systemdesign.ticketmaster.booking.api.model.AdmissionStatusResponse;
import com.systemdesign.ticketmaster.booking.api.model.WaitingRoomEntryResponse;
import com.systemdesign.ticketmaster.booking.application.AdmissionDecision;
import com.systemdesign.ticketmaster.booking.application.CheckAdmissionHandler;
import com.systemdesign.ticketmaster.booking.application.CheckAdmissionQuery;
import com.systemdesign.ticketmaster.booking.application.JoinWaitingRoomCommand;
import com.systemdesign.ticketmaster.booking.application.JoinWaitingRoomHandler;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class WaitingRoomApiController implements WaitingRoomApi {
    private final JoinWaitingRoomHandler joinWaitingRoomHandler;
    private final CheckAdmissionHandler checkAdmissionHandler;

    public WaitingRoomApiController(
            JoinWaitingRoomHandler joinWaitingRoomHandler,
            CheckAdmissionHandler checkAdmissionHandler) {
        this.joinWaitingRoomHandler = joinWaitingRoomHandler;
        this.checkAdmissionHandler = checkAdmissionHandler;
    }

    @Override
    public ResponseEntity<WaitingRoomEntryResponse> joinWaitingRoom(String eventId, String userId) {
        WaitingRoomEntry entry = joinWaitingRoomHandler.handle(
                new JoinWaitingRoomCommand(new EventId(eventId), new UserId(userId)));

        WaitingRoomEntryResponse response = new WaitingRoomEntryResponse();
        response.setEventId(entry.eventId().value());
        response.setUserId(entry.userId().value());
        response.setJoinedAt(entry.joinedAt().atOffset(ZoneOffset.UTC));
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<AdmissionStatusResponse> getAdmissionStatus(String eventId, String userId) {
        AdmissionDecision decision = checkAdmissionHandler.handle(
                new CheckAdmissionQuery(new EventId(eventId), new UserId(userId)));

        AdmissionStatusResponse response = new AdmissionStatusResponse();
        response.setStatus(AdmissionStatusResponse.StatusEnum.fromValue(decision.name()));
        return ResponseEntity.ok(response);
    }
}
