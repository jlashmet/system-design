package com.systemdesign.ticketmaster.controlplane.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.controlplane.domain.EventId;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnership;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnershipRepository;
import com.systemdesign.ticketmaster.controlplane.domain.EventWriterFence;
import com.systemdesign.ticketmaster.controlplane.domain.OwnershipConflictException;
import com.systemdesign.ticketmaster.controlplane.domain.RegionId;
import com.systemdesign.ticketmaster.controlplane.domain.WriterFenceNotConfirmedException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OwnershipHandlersTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final RegionId WEST = new RegionId("us-west-2");
    private static final RegionId EAST = new RegionId("us-east-1");

    private InMemoryOwnershipRepository repository;
    private TrackingFence fence;
    private EventOwnership result;
    private Throwable thrown;

    @Test
    void initialAssignmentStartsAtEpochOne() {
        givenUnassignedEvent();
        whenInitialOwnerIsAssigned();
        thenExpectInitialOwnership();
    }

    @Test
    void transferFencesExpectedGenerationBeforeOwnershipCas() {
        givenWestOwnershipWithConfirmedFence();
        whenOwnershipTransfersEast();
        thenExpectFencedEastOwnership();
    }

    @Test
    void staleTransferIsRejectedBeforeFencing() {
        givenEastOwnershipAtEpochTwo();
        whenStaleWestTransferIsAttempted();
        thenExpectConflictWithoutFencing();
    }

    @Test
    void unconfirmedFencePreventsOwnershipMutation() {
        givenWestOwnershipWithUnconfirmedFence();
        whenOwnershipTransfersEast();
        thenExpectFenceFailureWithoutTransfer();
    }

    private void givenUnassignedEvent() {
        repository = new InMemoryOwnershipRepository();
        fence = new TrackingFence(true);
        result = null;
        thrown = null;
    }

    private void givenWestOwnershipWithConfirmedFence() {
        givenUnassignedEvent();
        repository.assignIfAbsent(new EventOwnership(EVENT_ID, WEST, 1));
    }

    private void givenEastOwnershipAtEpochTwo() {
        givenUnassignedEvent();
        repository.assignIfAbsent(new EventOwnership(EVENT_ID, EAST, 2));
    }

    private void givenWestOwnershipWithUnconfirmedFence() {
        givenWestOwnershipWithConfirmedFence();
        fence = new TrackingFence(false);
    }

    private void whenInitialOwnerIsAssigned() {
        capture(() -> new AssignEventOwnershipHandler(repository)
                .handle(new AssignEventOwnershipCommand(EVENT_ID, WEST)));
    }

    private void whenOwnershipTransfersEast() {
        capture(() -> new TransferEventOwnershipHandler(repository, fence)
                .handle(new TransferEventOwnershipCommand(EVENT_ID, WEST, 1, EAST)));
    }

    private void whenStaleWestTransferIsAttempted() {
        capture(() -> new TransferEventOwnershipHandler(repository, fence)
                .handle(new TransferEventOwnershipCommand(EVENT_ID, WEST, 1, EAST)));
    }

    private void thenExpectInitialOwnership() {
        assertThat(thrown).isNull();
        assertThat(result).isEqualTo(new EventOwnership(EVENT_ID, WEST, 1));
    }

    private void thenExpectFencedEastOwnership() {
        assertThat(thrown).isNull();
        assertThat(result).isEqualTo(new EventOwnership(EVENT_ID, EAST, 2));
        assertThat(fence.calls).isEqualTo(1);
        assertThat(fence.eventId).isEqualTo(EVENT_ID);
        assertThat(fence.ownerRegion).isEqualTo(WEST);
        assertThat(fence.ownershipEpoch).isEqualTo(1);
        assertThat(repository.transferCalls).isEqualTo(1);
        assertThat(fence.callOrder).isLessThan(repository.transferCallOrder);
    }

    private void thenExpectConflictWithoutFencing() {
        assertThat(thrown).isInstanceOf(OwnershipConflictException.class);
        assertThat(result).isNull();
        assertThat(fence.calls).isZero();
        assertThat(repository.transferCalls).isZero();
        assertThat(repository.findByEventId(EVENT_ID)).contains(new EventOwnership(EVENT_ID, EAST, 2));
    }

    private void thenExpectFenceFailureWithoutTransfer() {
        assertThat(thrown).isInstanceOf(WriterFenceNotConfirmedException.class);
        assertThat(result).isNull();
        assertThat(fence.calls).isEqualTo(1);
        assertThat(repository.transferCalls).isZero();
        assertThat(repository.findByEventId(EVENT_ID)).contains(new EventOwnership(EVENT_ID, WEST, 1));
    }

    private void capture(OwnershipOperation operation) {
        result = null;
        thrown = null;
        try {
            result = operation.run();
        } catch (Throwable error) {
            thrown = error;
        }
    }

    @FunctionalInterface
    private interface OwnershipOperation {
        EventOwnership run();
    }

    private static final class TrackingFence implements EventWriterFence {
        private final boolean confirmed;
        private int calls;
        private EventId eventId;
        private RegionId ownerRegion;
        private long ownershipEpoch;
        private long callOrder;

        private TrackingFence(boolean confirmed) {
            this.confirmed = confirmed;
        }

        @Override
        public void assertFenced(EventId eventId, RegionId ownerRegion, long ownershipEpoch) {
            calls++;
            this.eventId = eventId;
            this.ownerRegion = ownerRegion;
            this.ownershipEpoch = ownershipEpoch;
            callOrder = Sequence.next();
            if (!confirmed) throw new WriterFenceNotConfirmedException(eventId, ownerRegion, ownershipEpoch);
        }
    }

    private static final class InMemoryOwnershipRepository implements EventOwnershipRepository {
        private EventOwnership ownership;
        private int transferCalls;
        private long transferCallOrder;

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
            transferCalls++;
            transferCallOrder = Sequence.next();
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

    private static final class Sequence {
        private static long value;

        private static long next() {
            return ++value;
        }
    }
}
