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

    @Test
    void createsClosedWatermarkAtStartupTimeWhenWaitingRoomIsNotYetEnabled() {
        FakeRepository repository = new FakeRepository();
        EnableAdmissionHandler handler = new EnableAdmissionHandler(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        EventAdmission admission = handler.handle(new EnableAdmissionCommand(EVENT_ID));

        assertThat(admission.admittedThrough()).isEqualTo(NOW);
        assertThat(repository.admission).isEqualTo(admission);
    }

    @Test
    void preservesExistingAdvancedWatermark() {
        FakeRepository repository = new FakeRepository();
        repository.admission = new EventAdmission(EVENT_ID, NOW.plusSeconds(10));
        EnableAdmissionHandler handler = new EnableAdmissionHandler(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        EventAdmission admission = handler.handle(new EnableAdmissionCommand(EVENT_ID));

        assertThat(admission).isEqualTo(repository.admission);
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
