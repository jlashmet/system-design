package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomDisabledException;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntryNotFoundException;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CheckAdmissionHandlerTest {
    private static final EventId EVENT_ID = new EventId("event-1");
    private static final UserId USER_ID = new UserId("user-1");
    private static final Instant JOINED_AT = Instant.parse("2026-08-28T20:00:00Z");

    private FakeRepository repository;
    private CheckAdmissionHandler handler;
    private AdmissionDecision decision;
    private Throwable thrown;

    @Test
    void disabledWaitingRoomIsRejectedBeforeEntryLookup() {
        givenDisabledWaitingRoom();
        whenAdmissionIsChecked();
        thenExpectFailure(WaitingRoomDisabledException.class);
    }

    @Test
    void enabledWaitingRoomWithoutJoinIsNotFound() {
        givenEnabledWaitingRoomWithoutEntry();
        whenAdmissionIsChecked();
        thenExpectFailure(WaitingRoomEntryNotFoundException.class);
    }

    @Test
    void userBeforeWatermarkIsAdmitted() {
        givenJoinedUserWithWatermarkAtJoin();
        whenAdmissionIsChecked();
        thenExpectDecision(AdmissionDecision.ADMITTED);
    }

    @Test
    void userAfterWatermarkIsWaiting() {
        givenJoinedUserWithWatermarkBeforeJoin();
        whenAdmissionIsChecked();
        thenExpectDecision(AdmissionDecision.WAITING);
    }

    private void givenDisabledWaitingRoom() {
        repository = new FakeRepository(null, null);
        handler = new CheckAdmissionHandler(repository);
        resetResult();
    }

    private void givenEnabledWaitingRoomWithoutEntry() {
        repository = new FakeRepository(new EventAdmission(EVENT_ID, JOINED_AT), null);
        handler = new CheckAdmissionHandler(repository);
        resetResult();
    }

    private void givenJoinedUserWithWatermarkAtJoin() {
        repository = new FakeRepository(
                new EventAdmission(EVENT_ID, JOINED_AT),
                new WaitingRoomEntry(EVENT_ID, USER_ID, JOINED_AT));
        handler = new CheckAdmissionHandler(repository);
        resetResult();
    }

    private void givenJoinedUserWithWatermarkBeforeJoin() {
        repository = new FakeRepository(
                new EventAdmission(EVENT_ID, JOINED_AT.minusMillis(1)),
                new WaitingRoomEntry(EVENT_ID, USER_ID, JOINED_AT));
        handler = new CheckAdmissionHandler(repository);
        resetResult();
    }

    private void whenAdmissionIsChecked() {
        try {
            decision = handler.handle(new CheckAdmissionQuery(EVENT_ID, USER_ID));
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectFailure(Class<? extends Throwable> type) {
        assertThat(thrown).isInstanceOf(type);
        assertThat(decision).isNull();
    }

    private void thenExpectDecision(AdmissionDecision expected) {
        assertThat(thrown).isNull();
        assertThat(decision).isEqualTo(expected);
    }

    private void resetResult() {
        decision = null;
        thrown = null;
    }

    private record FakeRepository(EventAdmission admission, WaitingRoomEntry entry)
            implements WaitingRoomRepository {
        @Override public WaitingRoomEntry join(WaitingRoomEntry entry) { throw new AssertionError("not expected"); }
        @Override public Optional<WaitingRoomEntry> findEntry(EventId eventId, UserId userId) {
            return Optional.ofNullable(entry);
        }
        @Override public Optional<EventAdmission> findAdmission(EventId eventId) {
            return Optional.ofNullable(admission);
        }
        @Override public EventAdmission advanceAdmission(EventAdmission admission) { throw new AssertionError("not expected"); }
    }
}
