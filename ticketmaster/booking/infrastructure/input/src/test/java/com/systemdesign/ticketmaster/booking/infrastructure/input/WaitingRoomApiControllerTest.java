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
import org.springframework.http.ResponseEntity;

class WaitingRoomApiControllerTest {
    private static final Instant JOINED_AT = Instant.parse("2026-08-27T20:00:00Z");

    private final FakeWaitingRoomRepository repository = new FakeWaitingRoomRepository();
    private final WaitingRoomApiController controller = new WaitingRoomApiController(
            new JoinWaitingRoomHandler(repository, Clock.fixed(JOINED_AT, ZoneOffset.UTC)),
            new CheckAdmissionHandler(repository));

    @Test
    void joinsUsingServerTimeAndMapsGeneratedResponse() {
        ResponseEntity<WaitingRoomEntryResponse> response = controller.joinWaitingRoom("event-1", "user-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getEventId()).isEqualTo("event-1");
        assertThat(response.getBody().getUserId()).isEqualTo("user-1");
        assertThat(response.getBody().getJoinedAt().toInstant()).isEqualTo(JOINED_AT);
    }

    @Test
    void reportsAdmittedWhenWatermarkPassesJoinTime() {
        controller.joinWaitingRoom("event-1", "user-1");
        repository.advanceAdmission(new EventAdmission(new EventId("event-1"), JOINED_AT));

        ResponseEntity<AdmissionStatusResponse> response = controller.getAdmissionStatus("event-1", "user-1");

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(AdmissionStatusResponse.StatusEnum.ADMITTED);
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
