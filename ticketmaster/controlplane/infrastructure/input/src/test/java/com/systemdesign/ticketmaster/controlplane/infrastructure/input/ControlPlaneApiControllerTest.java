package com.systemdesign.ticketmaster.controlplane.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.controlplane.api.model.EventOwnershipResponse;
import com.systemdesign.ticketmaster.controlplane.application.GetEventOwnershipHandler;
import com.systemdesign.ticketmaster.controlplane.domain.EventId;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnership;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnershipRepository;
import com.systemdesign.ticketmaster.controlplane.domain.RegionId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ControlPlaneApiControllerTest {

    @Test
    void mapsAuthoritativeOwnership() {
        EventOwnershipRepository repository = new FakeRepository(
                new EventOwnership(new EventId("event-123"), new RegionId("us-west-2"), 7));
        ControlPlaneApiController controller = new ControlPlaneApiController(new GetEventOwnershipHandler(repository));

        ResponseEntity<EventOwnershipResponse> response = controller.getEventOwnership("event-123");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getEventId()).isEqualTo("event-123");
        assertThat(response.getBody().getOwnerRegion()).isEqualTo("us-west-2");
        assertThat(response.getBody().getEpoch()).isEqualTo(7L);
    }

    @Test
    void returnsNotFoundWhenOwnershipIsUnassigned() {
        ControlPlaneApiController controller = new ControlPlaneApiController(
                new GetEventOwnershipHandler(new FakeRepository(null)));

        ResponseEntity<EventOwnershipResponse> response = controller.getEventOwnership("event-missing");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNull();
    }

    private static final class FakeRepository implements EventOwnershipRepository {
        private final EventOwnership ownership;

        private FakeRepository(EventOwnership ownership) {
            this.ownership = ownership;
        }

        @Override
        public Optional<EventOwnership> findByEventId(EventId eventId) {
            return Optional.ofNullable(ownership).filter(value -> value.eventId().equals(eventId));
        }

        @Override
        public EventOwnership assignIfAbsent(EventOwnership ownership) {
            throw new AssertionError("not expected");
        }

        @Override
        public EventOwnership transfer(EventId eventId, RegionId expectedOwner, long expectedEpoch, RegionId newOwner) {
            throw new AssertionError("not expected");
        }
    }
}
