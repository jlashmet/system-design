package com.systemdesign.ticketmaster.controlplane.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.controlplane.domain.EventId;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnership;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnershipRepository;
import com.systemdesign.ticketmaster.controlplane.domain.OwnershipConflictException;
import com.systemdesign.ticketmaster.controlplane.domain.RegionId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OwnershipHandlersTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final RegionId WEST = new RegionId("us-west-2");
    private static final RegionId EAST = new RegionId("us-east-1");

    @Test
    void initialAssignmentStartsAtEpochOne() {
        InMemoryOwnershipRepository repository = new InMemoryOwnershipRepository();
        AssignEventOwnershipHandler handler = new AssignEventOwnershipHandler(repository);

        EventOwnership ownership = handler.handle(new AssignEventOwnershipCommand(EVENT_ID, WEST));

        assertThat(ownership).isEqualTo(new EventOwnership(EVENT_ID, WEST, 1));
    }

    @Test
    void transferRequiresExpectedOwnerAndEpochAndIncrementsEpoch() {
        InMemoryOwnershipRepository repository = new InMemoryOwnershipRepository();
        new AssignEventOwnershipHandler(repository).handle(new AssignEventOwnershipCommand(EVENT_ID, WEST));
        TransferEventOwnershipHandler handler = new TransferEventOwnershipHandler(repository);

        EventOwnership ownership = handler.handle(new TransferEventOwnershipCommand(EVENT_ID, WEST, 1, EAST));

        assertThat(ownership).isEqualTo(new EventOwnership(EVENT_ID, EAST, 2));
        assertThatThrownBy(() -> handler.handle(new TransferEventOwnershipCommand(EVENT_ID, WEST, 1, EAST)))
                .isInstanceOf(OwnershipConflictException.class);
    }

    private static final class InMemoryOwnershipRepository implements EventOwnershipRepository {
        private EventOwnership ownership;

        @Override
        public Optional<EventOwnership> findByEventId(EventId eventId) {
            return ownership != null && ownership.eventId().equals(eventId) ? Optional.of(ownership) : Optional.empty();
        }

        @Override
        public EventOwnership assignIfAbsent(EventOwnership requested) {
            if (ownership != null) throw new OwnershipConflictException("already assigned");
            ownership = requested;
            return ownership;
        }

        @Override
        public EventOwnership transfer(EventId eventId, RegionId expectedOwner, long expectedEpoch, RegionId newOwner) {
            if (ownership == null
                    || !ownership.eventId().equals(eventId)
                    || !ownership.ownerRegion().equals(expectedOwner)
                    || ownership.epoch() != expectedEpoch) {
                throw new OwnershipConflictException("stale ownership");
            }
            ownership = new EventOwnership(eventId, newOwner, expectedEpoch + 1);
            return ownership;
        }
    }
}
