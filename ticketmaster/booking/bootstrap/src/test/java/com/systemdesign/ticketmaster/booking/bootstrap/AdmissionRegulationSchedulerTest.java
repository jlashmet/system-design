package com.systemdesign.ticketmaster.booking.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.systemdesign.ticketmaster.booking.application.EnableAdmissionHandler;
import com.systemdesign.ticketmaster.booking.application.RegulateAdmissionHandler;
import com.systemdesign.ticketmaster.booking.domain.AdmissionCapacity;
import com.systemdesign.ticketmaster.booking.domain.AdmissionHealthGateway;
import com.systemdesign.ticketmaster.booking.domain.AdmissionRegulationLeaseGateway;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import com.systemdesign.ticketmaster.booking.domain.WrongBookingRegionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdmissionRegulationSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-28T17:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration LEASE_DURATION = Duration.ofSeconds(5);
    private static final EventId FIRST = new EventId("event-1");
    private static final EventId SECOND = new EventId("event-2");

    private FakeWaitingRoomRepository repository;
    private FakeLeaseGateway leaseGateway;
    private TrackingHealthGateway health;
    private EventWriteAuthority authority;
    private AdmissionRegulationScheduler scheduler;
    private AdmissionRegulationScheduler secondScheduler;
    private Throwable thrown;

    @Test
    void initializesConfiguredEventsBeforeRegulation() {
        givenConfiguredEventsWithoutAdmission();
        whenSchedulerIsConstructed();
        thenExpectConfiguredEventsInitializedClosed();
    }

    @Test
    void continuesWithOtherEventsWhenOneRegulationFails() {
        givenEnabledEventsAndFirstHealthCheckFailure();
        whenRegulationRuns();
        thenExpectOtherEventsStillRegulated();
    }

    @Test
    void multipleBookingReplicasDoNotMultiplyAdmissionRate() {
        givenTwoRegulatorsForSameHealthyEvent();
        whenBothReplicasRegulate();
        thenExpectOnlyLeaseOwnerAdvancesAdmission();
    }

    @Test
    void nonOwnerRegionDoesNotCompeteForAdmissionLease() {
        givenRegulatorInNonOwnerRegion();
        whenRegulationRuns();
        thenExpectNonOwnerRegionSkipsRegulation();
    }

    private void givenConfiguredEventsWithoutAdmission() {
        repository = new FakeWaitingRoomRepository();
        leaseGateway = new FakeLeaseGateway();
        health = new TrackingHealthGateway(false, AdmissionCapacity.OVERLOADED);
        authority = ignored -> {};
        scheduler = null;
        secondScheduler = null;
        thrown = null;
    }

    private void givenEnabledEventsAndFirstHealthCheckFailure() {
        repository = new FakeWaitingRoomRepository();
        repository.admissions.put(FIRST, new EventAdmission(FIRST, NOW.minusSeconds(10)));
        repository.admissions.put(SECOND, new EventAdmission(SECOND, NOW.minusSeconds(10)));
        leaseGateway = new FakeLeaseGateway();
        health = new TrackingHealthGateway(true, AdmissionCapacity.OVERLOADED);
        authority = ignored -> {};
        scheduler = scheduler("regulator-a", List.of(FIRST, SECOND));
        secondScheduler = null;
        thrown = null;
    }

    private void givenTwoRegulatorsForSameHealthyEvent() {
        repository = new FakeWaitingRoomRepository();
        repository.admissions.put(FIRST, new EventAdmission(FIRST, NOW.minusSeconds(10)));
        leaseGateway = new FakeLeaseGateway();
        health = new TrackingHealthGateway(false, AdmissionCapacity.HEALTHY);
        authority = ignored -> {};
        scheduler = scheduler("regulator-a", List.of(FIRST));
        secondScheduler = scheduler("regulator-b", List.of(FIRST));
        thrown = null;
    }

    private void givenRegulatorInNonOwnerRegion() {
        repository = new FakeWaitingRoomRepository();
        repository.admissions.put(FIRST, new EventAdmission(FIRST, NOW.minusSeconds(10)));
        leaseGateway = new FakeLeaseGateway();
        health = new TrackingHealthGateway(false, AdmissionCapacity.HEALTHY);
        authority = eventId -> {
            throw new WrongBookingRegionException(eventId, "us-east-1", "us-west-2");
        };
        scheduler = scheduler("regulator-west", List.of(FIRST));
        secondScheduler = null;
        thrown = null;
    }

    private void whenSchedulerIsConstructed() {
        scheduler = scheduler("regulator-a", List.of(FIRST, SECOND));
    }

    private void whenRegulationRuns() {
        try {
            scheduler.regulate();
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void whenBothReplicasRegulate() {
        try {
            scheduler.regulate();
            secondScheduler.regulate();
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectConfiguredEventsInitializedClosed() {
        assertThat(repository.admissions).containsKeys(FIRST, SECOND);
        assertThat(repository.admissions.get(FIRST).admittedThrough()).isEqualTo(NOW.minusMillis(1));
        assertThat(repository.admissions.get(SECOND).admittedThrough()).isEqualTo(NOW.minusMillis(1));
    }

    private void thenExpectOtherEventsStillRegulated() {
        assertThatCode(() -> {
            if (thrown != null) throw thrown;
        }).doesNotThrowAnyException();
        assertThat(health.assessed).containsExactly(FIRST, SECOND);
    }

    private void thenExpectOnlyLeaseOwnerAdvancesAdmission() {
        assertThat(thrown).isNull();
        assertThat(health.assessed).containsExactly(FIRST);
        assertThat(repository.admissions.get(FIRST).admittedThrough())
                .isEqualTo(NOW.minusSeconds(8));
        assertThat(leaseGateway.owner(FIRST)).contains("regulator-a");
    }

    private void thenExpectNonOwnerRegionSkipsRegulation() {
        assertThat(thrown).isNull();
        assertThat(health.assessed).isEmpty();
        assertThat(leaseGateway.owner(FIRST)).isEmpty();
        assertThat(repository.admissions.get(FIRST).admittedThrough()).isEqualTo(NOW.minusSeconds(10));
    }

    private AdmissionRegulationScheduler scheduler(String regulatorId, List<EventId> eventIds) {
        return new AdmissionRegulationScheduler(
                new EnableAdmissionHandler(repository, FIXED_CLOCK),
                authority,
                handler(),
                leaseGateway,
                FIXED_CLOCK,
                LEASE_DURATION,
                regulatorId,
                eventIds);
    }

    private RegulateAdmissionHandler handler() {
        return new RegulateAdmissionHandler(
                repository,
                health,
                FIXED_CLOCK,
                Duration.ofSeconds(2),
                Duration.ofMillis(500));
    }

    private static final class TrackingHealthGateway implements AdmissionHealthGateway {
        private final boolean failFirst;
        private final AdmissionCapacity capacity;
        private final List<EventId> assessed = new ArrayList<>();

        private TrackingHealthGateway(boolean failFirst, AdmissionCapacity capacity) {
            this.failFirst = failFirst;
            this.capacity = capacity;
        }

        @Override
        public AdmissionCapacity assess(EventId eventId) {
            assessed.add(eventId);
            if (failFirst && eventId.equals(FIRST)) throw new IllegalStateException("synthetic health failure");
            return capacity;
        }
    }

    private static final class FakeLeaseGateway implements AdmissionRegulationLeaseGateway {
        private final Map<EventId, Lease> leases = new HashMap<>();

        @Override
        public boolean tryAcquireOrRenew(
                EventId eventId,
                String regulatorId,
                Instant now,
                Instant leaseExpiresAt) {
            Lease current = leases.get(eventId);
            if (current != null && current.expiresAt().isAfter(now) && !current.regulatorId().equals(regulatorId)) {
                return false;
            }
            leases.put(eventId, new Lease(regulatorId, leaseExpiresAt));
            return true;
        }

        Optional<String> owner(EventId eventId) {
            return Optional.ofNullable(leases.get(eventId)).map(Lease::regulatorId);
        }

        private record Lease(String regulatorId, Instant expiresAt) {}
    }

    private static final class FakeWaitingRoomRepository implements WaitingRoomRepository {
        private final Map<EventId, EventAdmission> admissions = new HashMap<>();

        @Override public WaitingRoomEntry join(WaitingRoomEntry entry) { return entry; }
        @Override public Optional<WaitingRoomEntry> findEntry(EventId eventId, UserId userId) { return Optional.empty(); }
        @Override public Optional<EventAdmission> findAdmission(EventId eventId) { return Optional.ofNullable(admissions.get(eventId)); }

        @Override
        public EventAdmission advanceAdmission(EventAdmission admission) {
            admissions.put(admission.eventId(), admission);
            return admission;
        }
    }
}
