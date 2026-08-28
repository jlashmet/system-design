package com.systemdesign.ticketmaster.controlplane.infrastructure.input;

import com.systemdesign.ticketmaster.controlplane.api.ControlPlaneApi;
import com.systemdesign.ticketmaster.controlplane.api.model.EventOwnershipResponse;
import com.systemdesign.ticketmaster.controlplane.application.GetEventOwnershipHandler;
import com.systemdesign.ticketmaster.controlplane.application.GetEventOwnershipQuery;
import com.systemdesign.ticketmaster.controlplane.domain.EventId;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnership;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ControlPlaneApiController implements ControlPlaneApi {
    private final GetEventOwnershipHandler getEventOwnershipHandler;

    public ControlPlaneApiController(GetEventOwnershipHandler getEventOwnershipHandler) {
        this.getEventOwnershipHandler = Objects.requireNonNull(getEventOwnershipHandler, "getEventOwnershipHandler");
    }

    @Override
    public ResponseEntity<EventOwnershipResponse> getEventOwnership(String eventId) {
        return getEventOwnershipHandler.handle(new GetEventOwnershipQuery(new EventId(eventId)))
                .map(ControlPlaneApiController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static EventOwnershipResponse toResponse(EventOwnership ownership) {
        EventOwnershipResponse response = new EventOwnershipResponse();
        response.setEventId(ownership.eventId().value());
        response.setOwnerRegion(ownership.ownerRegion().value());
        response.setEpoch(ownership.epoch());
        return response;
    }
}
