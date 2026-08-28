package com.systemdesign.ticketmaster.booking.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.api.model.AdmissionStatusResponse;
import com.systemdesign.ticketmaster.booking.api.model.WaitingRoomEntryResponse;
import com.systemdesign.ticketmaster.booking.application.CheckAdmissionHandler;
import com.systemdesign.ticketmaster.booking.application.JoinWaitingRoomHandler;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class WaitingRoomApiControllerTest {
    private static final Instant JOINED_AT = Instant.parse("2026-08-27T20:00:00Z");
    private static final EventId EVENT_ID = new EventId("event-1");

    private FakeWaitingRoomRepository repository;
    private WaitingRoomApiController controller;
    private ResponseEntity<WaitingRoomEntryResponse> joinResponse;
    private ResponseEntity<AdmissionStatusResponse> admissionResponse;

    @Test
    void joinsEnabledWaitingRoomUsingServerTimeAndDoesNotCache() {
        givenEnabledWaitingRoom(JOINED_AT.minusSeconds(1));
        whenUserJoinsWaitingRoom();
        thenExpectServerTimestampedJoinResponse();
    }

    @Test
    void reportsWaitingWhenWatermarkHasNotReachedJoinTime() {
        givenEnabledWaitingRoom(JOINED_AT.minusSeconds(1));
        whenUserJoinsAndChecksAdmission();
        thenExpectAdmissionStatus(AdmissionStatusResponse.StatusEnum.WAITING);
    }

    @Test
    void reportsAdmittedWhenWatermarkPassesJoinTime() {
        givenEnabledWaitingRoom(JOINED_AT);
        whenUserJoinsAndChecksAdmission();
        thenExpectAdmissionStatus(AdmissionStatusResponse.StatusEnum.ADMITTED);
    }

    private void givenEnabledWaitingRoom(Instant admittedThrough) {
        repository = new FakeWaitingRoomRepository();
        repository.advanceAdmission(new EventAdmission(EVENT_ID, admittedThrough));
        controller = new WaitingRoomApiController(
                new JoinWaitingRoomHandler(repository, Clock.fixed(JOINED_AT, ZoneOffset.UTC)),
                new CheckAdmissionHandler(repository));
        joinResponse = null;
        admissionResponse = null;
    }

    private void whenUserJoinsWaitingRoom() {
        joinResponse = controller.joinWaitingRoom(EVENT_ID.value(), "user-1");
    }

    private void whenUserJoinsAndChecksAdmission() {
        controller.joinWaitingRoom(EVENT_ID.value(), "user-1");
        admissionResponse = controller.getAdmissionStatus(EVENT_ID.value(), "user-1");
    }

    private void thenExpectServerTimestampedJoinResponse() {
        assertThat(joinResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(joinResponse.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(joinResponse.getBody()).isNotNull();
        assertThat(joinResponse.getBody().getEventId()).isEqualTo(EVENT_ID.value());
        assertThat(joinResponse.getBody().getUserId()).isEqualTo("user-1");
        assertThat(joinResponse.getBody().getJoinedAt().toInstant()).isEqualTo(JOINED_AT);
    }

    private void thenExpectAdmissionStatus(AdmissionStatusResponse.StatusEnum status) {
        assertThat(admissionResponse.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(admissionResponse.getBody()).isNotNull();
        assertThat(admissionResponse.getBody().getStatus()).isEqualTo(status);
    }

    private static final class FakeWaitingRoomRepository implements WaitingRoomRepository {
        private final Map<String, WaitingRoomEntry> entries = new HashMap<>();
        private final Map<EventId, EventAdmission> admissions = new HashMap<>();

        @Override
        public WaitingRoomEntry join(WaitingRoomEntry entry) {
            return entries.computeIfAbsent(key(entry.eventId(), entry.userId()), ignored -> entry);
        }

        @Override
        public Optional<WaitingRoomEntry> findEntry(EventId eventId, UserId userId) {
            return Optional.ofNullable(entries.get(key(eventId, userId)));
        }

        @Override
        public Optional<EventAdmission> findAdmission(EventId eventId) {
            return Optional.ofNullable(admissions.get(eventId));
        }

        @Override
        public EventAdmission advanceAdmission(EventAdmission admission) {
            admissions.put(admission.eventId(), admission);
            return admission;
        }

        private static String key(EventId eventId, UserId userId) {
            return eventId.value() + "#" + userId.value();
        }
    }
}
