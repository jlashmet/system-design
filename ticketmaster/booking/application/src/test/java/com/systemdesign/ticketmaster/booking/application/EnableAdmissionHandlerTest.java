package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EnableAdmissionHandlerTest {
    private static final EventId EVENT_ID = new EventId("event-1");
    private static final Instant NOW = Instant.parse("2026-08-28T18:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private FakeRepository repository;
    private EnableAdmissionHandler handler;
    private EventAdmission admission;

    @Test
    void createsClosedWatermarkImmediatelyBeforeStartupTimeWhenWaitingRoomIsNotYetEnabled() {
        givenWaitingRoomDisabled();
        whenAdmissionIsEnabled();
        thenExpectClosedWatermarkImmediatelyBeforeStartupTime();
    }

    @Test
    void preservesExistingAdvancedWatermark() {
        givenExistingAdvancedWatermark();
        whenAdmissionIsEnabled();
        thenExpectExistingWatermarkPreserved();
    }

    private void givenWaitingRoomDisabled() {
        repository = new FakeRepository();
        handler = new EnableAdmissionHandler(repository, FIXED_CLOCK);
        admission = null;
    }

    private void givenExistingAdvancedWatermark() {
        repository = new FakeRepository();
        repository.admission = new EventAdmission(EVENT_ID, NOW.plusSeconds(10));
        handler = new EnableAdmissionHandler(repository, FIXED_CLOCK);
        admission = null;
    }

    private void whenAdmissionIsEnabled() {
        admission = handler.handle(new EnableAdmissionCommand(EVENT_ID));
    }

    private void thenExpectClosedWatermarkImmediatelyBeforeStartupTime() {
        assertThat(admission.admittedThrough()).isEqualTo(NOW.minusMillis(1));
        assertThat(repository.admission).isEqualTo(admission);
        assertThat(repository.advanceCalls).isOne();
    }

    private void thenExpectExistingWatermarkPreserved() {
        assertThat(admission).isEqualTo(repository.admission);
        assertThat(admission.admittedThrough()).isEqualTo(NOW.plusSeconds(10));
        assertThat(repository.advanceCalls).isZero();
    }

    private static final class FakeRepository implements WaitingRoomRepository {
        private EventAdmission admission;
        private int advanceCalls;

        @Override public WaitingRoomEntry join(WaitingRoomEntry entry) { return entry; }
        @Override public Optional<WaitingRoomEntry> findEntry(EventId eventId, UserId userId) { return Optional.empty(); }
        @Override public Optional<EventAdmission> findAdmission(EventId eventId) { return Optional.ofNullable(admission); }

        @Override
        public EventAdmission advanceAdmission(EventAdmission admission) {
            advanceCalls++;
            this.admission = admission;
            return admission;
        }
    }
}
