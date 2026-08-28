package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomDisabledException;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JoinWaitingRoomHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-28T18:00:00Z");
    private static final EventId EVENT_ID = new EventId("event-1");
    private static final UserId USER_ID = new UserId("user-1");

    private FakeRepository repository;
    private JoinWaitingRoomHandler handler;
    private WaitingRoomEntry entry;
    private Throwable thrown;

    @Test
    void disabledWaitingRoomRejectsJoinWithoutCreatingEntry() {
        givenWaitingRoomDisabled();
        whenUserJoins();
        thenExpectDisabledWithoutEntry();
    }

    @Test
    void enabledWaitingRoomAcceptsJoinAtServerTime() {
        givenWaitingRoomEnabled();
        whenUserJoins();
        thenExpectJoinedAtServerTime();
    }

    private void givenWaitingRoomDisabled() {
        repository = new FakeRepository(null);
        handler = new JoinWaitingRoomHandler(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        entry = null;
        thrown = null;
    }

    private void givenWaitingRoomEnabled() {
        repository = new FakeRepository(new EventAdmission(EVENT_ID, NOW.minusSeconds(1)));
        handler = new JoinWaitingRoomHandler(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        entry = null;
        thrown = null;
    }

    private void whenUserJoins() {
        try {
            entry = handler.handle(new JoinWaitingRoomCommand(EVENT_ID, USER_ID));
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectDisabledWithoutEntry() {
        assertThat(thrown).isInstanceOf(WaitingRoomDisabledException.class);
        assertThat(repository.joinCalls).isZero();
        assertThat(entry).isNull();
    }

    private void thenExpectJoinedAtServerTime() {
        assertThat(thrown).isNull();
        assertThat(repository.joinCalls).isOne();
        assertThat(entry).isEqualTo(new WaitingRoomEntry(EVENT_ID, USER_ID, NOW));
    }

    private static final class FakeRepository implements WaitingRoomRepository {
        private final EventAdmission admission;
        private int joinCalls;

        private FakeRepository(EventAdmission admission) {
            this.admission = admission;
        }

        @Override
        public WaitingRoomEntry join(WaitingRoomEntry entry) {
            joinCalls++;
            return entry;
        }

        @Override public Optional<WaitingRoomEntry> findEntry(EventId eventId, UserId userId) { return Optional.empty(); }
        @Override public Optional<EventAdmission> findAdmission(EventId eventId) { return Optional.ofNullable(admission); }
        @Override public EventAdmission advanceAdmission(EventAdmission admission) { return admission; }
    }
}
