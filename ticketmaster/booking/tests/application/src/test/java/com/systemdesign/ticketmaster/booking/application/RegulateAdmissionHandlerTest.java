package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.AdmissionCapacity;
import com.systemdesign.ticketmaster.booking.domain.AdmissionHealthGateway;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RegulateAdmissionHandlerTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final Instant NOW = Instant.parse("2026-08-28T17:00:00Z");

    @Test
    void healthyCapacityAdvancesWatermarkByLargerStep() {
        FakeWaitingRoomRepository repository = new FakeWaitingRoomRepository(
                new EventAdmission(EVENT_ID, NOW.minusSeconds(10)));
        RegulateAdmissionHandler handler = handler(repository, AdmissionCapacity.HEALTHY);

        AdmissionRegulationResult result = handler.handle(new RegulateAdmissionCommand(EVENT_ID));

        assertThat(result.advanced()).isTrue();
        assertThat(result.capacity()).isEqualTo(AdmissionCapacity.HEALTHY);
        assertThat(result.admission().orElseThrow().admittedThrough()).isEqualTo(NOW.minusMillis(9750));
        assertThat(repository.advanceCalls).isEqualTo(1);
    }

    @Test
    void constrainedCapacityAdvancesWatermarkBySmallerStep() {
        FakeWaitingRoomRepository repository = new FakeWaitingRoomRepository(
                new EventAdmission(EVENT_ID, NOW.minusSeconds(10)));
        RegulateAdmissionHandler handler = handler(repository, AdmissionCapacity.CONSTRAINED);

        AdmissionRegulationResult result = handler.handle(new RegulateAdmissionCommand(EVENT_ID));

        assertThat(result.advanced()).isTrue();
        assertThat(result.admission().orElseThrow().admittedThrough()).isEqualTo(NOW.minusMillis(9950));
    }

    @Test
    void overloadedCapacityStopsAdmission() {
        EventAdmission current = new EventAdmission(EVENT_ID, NOW.minusSeconds(10));
        FakeWaitingRoomRepository repository = new FakeWaitingRoomRepository(current);
        RegulateAdmissionHandler handler = handler(repository, AdmissionCapacity.OVERLOADED);

        AdmissionRegulationResult result = handler.handle(new RegulateAdmissionCommand(EVENT_ID));

        assertThat(result.advanced()).isFalse();
        assertThat(result.admission()).contains(current);
        assertThat(repository.advanceCalls).isZero();
    }

    @Test
    void neverAdvancesBeyondCurrentServerTime() {
        FakeWaitingRoomRepository repository = new FakeWaitingRoomRepository(
                new EventAdmission(EVENT_ID, NOW.minusMillis(100)));
        RegulateAdmissionHandler handler = handler(repository, AdmissionCapacity.HEALTHY);

        AdmissionRegulationResult result = handler.handle(new RegulateAdmissionCommand(EVENT_ID));

        assertThat(result.admission().orElseThrow().admittedThrough()).isEqualTo(NOW);
    }

    @Test
    void disabledWaitingRoomDoesNotConsultHealthOrCreateAdmissionState() {
        FakeWaitingRoomRepository repository = new FakeWaitingRoomRepository(null);
        TrackingHealthGateway health = new TrackingHealthGateway(AdmissionCapacity.HEALTHY);
        RegulateAdmissionHandler handler = new RegulateAdmissionHandler(
                repository,
                health,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMillis(250),
                Duration.ofMillis(50));

        AdmissionRegulationResult result = handler.handle(new RegulateAdmissionCommand(EVENT_ID));

        assertThat(result.admission()).isEmpty();
        assertThat(result.advanced()).isFalse();
        assertThat(health.calls).isZero();
        assertThat(repository.advanceCalls).isZero();
    }

    private static RegulateAdmissionHandler handler(
            FakeWaitingRoomRepository repository, AdmissionCapacity capacity) {
        return new RegulateAdmissionHandler(
                repository,
                new TrackingHealthGateway(capacity),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMillis(250),
                Duration.ofMillis(50));
    }

    private static final class TrackingHealthGateway implements AdmissionHealthGateway {
        private final AdmissionCapacity capacity;
        private int calls;

        private TrackingHealthGateway(AdmissionCapacity capacity) {
            this.capacity = capacity;
        }

        @Override
        public AdmissionCapacity assess(EventId eventId) {
            calls++;
            return capacity;
        }
    }

    private static final class FakeWaitingRoomRepository implements WaitingRoomRepository {
        private EventAdmission admission;
        private int advanceCalls;

        private FakeWaitingRoomRepository(EventAdmission admission) {
            this.admission = admission;
        }

        @Override public WaitingRoomEntry join(WaitingRoomEntry entry) { return entry; }
        @Override public Optional<WaitingRoomEntry> findEntry(EventId eventId, UserId userId) { return Optional.empty(); }
        @Override public Optional<EventAdmission> findAdmission(EventId eventId) {
            return Optional.ofNullable(admission).filter(value -> value.eventId().equals(eventId));
        }
        @Override public EventAdmission advanceAdmission(EventAdmission admission) {
            advanceCalls++;
            this.admission = admission;
            return admission;
        }
    }
}
