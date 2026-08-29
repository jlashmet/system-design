package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.AdmissionGrant;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HmacAdmissionGrantServiceTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final Instant NOW = Instant.parse("2026-08-29T13:30:00Z");

    private HmacAdmissionGrantService service;
    private AdmissionGrant grant;
    private boolean accepted;

    @Test
    void issuedGrantIsAcceptedForItsEventAndUser() {
        givenGrantIssued();
        whenGrantChecked(EVENT_ID, USER_ID, grant.token(), NOW.plusSeconds(1));
        thenExpectAccepted(true, NOW.plusSeconds(30));
    }

    @Test
    void grantIsRejectedAtExpiration() {
        givenGrantIssued();
        whenGrantChecked(EVENT_ID, USER_ID, grant.token(), NOW.plusSeconds(30));
        thenExpectAccepted(false, NOW.plusSeconds(30));
    }

    @Test
    void grantIsRejectedForDifferentEvent() {
        givenGrantIssued();
        whenGrantChecked(new EventId("event-other"), USER_ID, grant.token(), NOW.plusSeconds(1));
        thenExpectAccepted(false, NOW.plusSeconds(30));
    }

    @Test
    void grantIsRejectedForDifferentUser() {
        givenGrantIssued();
        whenGrantChecked(EVENT_ID, new UserId("user-other"), grant.token(), NOW.plusSeconds(1));
        thenExpectAccepted(false, NOW.plusSeconds(30));
    }

    @Test
    void tamperedGrantIsRejected() {
        givenGrantIssued();
        whenGrantChecked(EVENT_ID, USER_ID, grant.token() + "x", NOW.plusSeconds(1));
        thenExpectAccepted(false, NOW.plusSeconds(30));
    }

    private void givenGrantIssued() {
        service = new HmacAdmissionGrantService("test-admission-secret", Duration.ofSeconds(30));
        grant = service.issue(EVENT_ID, USER_ID, NOW).orElseThrow();
        accepted = false;
    }

    private void whenGrantChecked(EventId eventId, UserId userId, String token, Instant now) {
        accepted = service.accepts(eventId, userId, token, now);
    }

    private void thenExpectAccepted(boolean expected, Instant expectedExpiry) {
        assertThat(accepted).isEqualTo(expected);
        assertThat(grant.expiresAt()).isEqualTo(expectedExpiry);
        assertThat(grant.token()).startsWith("v1.");
    }
}
