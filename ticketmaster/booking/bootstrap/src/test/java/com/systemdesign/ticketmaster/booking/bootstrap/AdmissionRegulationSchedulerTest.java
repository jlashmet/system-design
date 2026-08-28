package com.systemdesign.ticketmaster.booking.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.systemdesign.ticketmaster.booking.application.EnableAdmissionHandler;
import com.systemdesign.ticketmaster.booking.application.RegulateAdmissionHandler;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdmissionRegulationSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-28T17:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final EventId FIRST = new EventId("event-1");
    private static final EventId SECOND = new EventId("event-2");

    private FakeWaitingRoomRepository repository;
    private TrackingHealthGateway health;
    private AdmissionRegulationScheduler scheduler;
    private Throwable thrown;

    @Test
    void initializesConfiguredEventsBeforeRegulation() {
        givenConfiguredEventsWithoutAdmission();
        whenSchedulerIsConstructed();
        thenExpectConfiguredEventsInitializedAtNow();
    }

    @Test
    void continuesWithOtherEventsWhenOneRegulationFails() {
        givenEnabledEventsAndFirstHealthCheckFailure();
        whenRegulationRuns();
        thenExpectOtherEventsStillRegulated();
    }

    private void givenConfiguredEventsWithoutAdmission() {
        repository = new FakeWaitingRoomRepository();
        health = new TrackingHealthGateway(false);
        scheduler = null;
        thrown = null;
    }

    private void givenEnabledEventsAndFirstHealthCheckFailure() {
        repository = new FakeWaitingRoomRepository();
        repository.admissions.put(FIRST, new EventAdmission(FIRST, NOW.minusSeconds(10)));
        repository.admissions.put(SECOND, new EventAdmission(SECOND, NOW.minusSeconds(10)));
        health = new TrackingHealthGateway(true);
        scheduler = new AdmissionRegulationScheduler(
                new EnableAdmissionHandler(repository, FIXED_CLOCK),
                handler(),
                List.of(FIRST, SECOND));
        thrown = null;
    }

    private void whenSchedulerIsConstructed() {
        scheduler = new AdmissionRegulationScheduler(
                new EnableAdmissionHandler(repository, FIXED_CLOCK),
                handler(),
                List.of(FIRST, SECOND));
    }

    private void whenRegulationRuns() {
        try {
            scheduler.regulate();
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectConfiguredEventsInitializedAtNow() {
        assertThat(repository.admissions).containsKeys(FIRST, SECOND);
        assertThat(repository.admissions.get(FIRST).admittedThrough()).isEqualTo(NOW);
        assertThat(repository.admissions.get(SECOND).admittedThrough()).isEqualTo(NOW);
    }

    private void thenExpectOtherEventsStillRegulated() {
        assertThatCode(() -> {
            if (thrown != null) throw thrown;
        }).doesNotThrowAnyException();
        assertThat(health.assessed).containsExactly(FIRST, SECOND);
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
        private final List<EventId> assessed = new ArrayList<>();

        private TrackingHealthGateway(boolean failFirst) {
            this.failFirst = failFirst;
        }

        @Override
        public AdmissionCapacity assess(EventId eventId) {
            assessed.add(eventId);
            if (failFirst && eventId.equals(FIRST)) throw new IllegalStateException("synthetic health failure");
            return AdmissionCapacity.OVERLOADED;
        }
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
