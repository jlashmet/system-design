package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventOwnershipUnavailableException;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CachedEventWriteAuthorityTest {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final Instant NOW = Instant.parse("2026-08-28T17:00:00Z");

    @Test
    void reusesSuccessfulOwnershipCheckUntilTtlExpires() {
        MutableClock clock = new MutableClock(NOW);
        TrackingAuthority delegate = new TrackingAuthority();
        CachedEventWriteAuthority authority = new CachedEventWriteAuthority(delegate, clock, Duration.ofSeconds(5));

        authority.assertMayWrite(EVENT_ID);
        authority.assertMayWrite(EVENT_ID);
        clock.advance(Duration.ofSeconds(4));
        authority.assertMayWrite(EVENT_ID);

        assertThat(delegate.calls).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        authority.assertMayWrite(EVENT_ID);

        assertThat(delegate.calls).isEqualTo(2);
    }

    @Test
    void doesNotCacheFailedOwnershipChecks() {
        MutableClock clock = new MutableClock(NOW);
        TrackingAuthority delegate = new TrackingAuthority();
        delegate.failure = new EventOwnershipUnavailableException(EVENT_ID, "control plane unavailable");
        CachedEventWriteAuthority authority = new CachedEventWriteAuthority(delegate, clock, Duration.ofSeconds(5));

        assertThatThrownBy(() -> authority.assertMayWrite(EVENT_ID))
                .isInstanceOf(EventOwnershipUnavailableException.class);
        assertThatThrownBy(() -> authority.assertMayWrite(EVENT_ID))
                .isInstanceOf(EventOwnershipUnavailableException.class);

        assertThat(delegate.calls).isEqualTo(2);
    }

    @Test
    void permitsAgainAfterTransientFailureRecovers() {
        MutableClock clock = new MutableClock(NOW);
        TrackingAuthority delegate = new TrackingAuthority();
        delegate.failure = new EventOwnershipUnavailableException(EVENT_ID, "control plane unavailable");
        CachedEventWriteAuthority authority = new CachedEventWriteAuthority(delegate, clock, Duration.ofSeconds(5));

        assertThatThrownBy(() -> authority.assertMayWrite(EVENT_ID))
                .isInstanceOf(EventOwnershipUnavailableException.class);
        delegate.failure = null;

        assertThatCode(() -> authority.assertMayWrite(EVENT_ID)).doesNotThrowAnyException();
        assertThat(delegate.calls).isEqualTo(2);
    }

    private static final class TrackingAuthority implements EventWriteAuthority {
        private int calls;
        private RuntimeException failure;

        @Override
        public void assertMayWrite(EventId eventId) {
            calls++;
            if (failure != null) throw failure;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
