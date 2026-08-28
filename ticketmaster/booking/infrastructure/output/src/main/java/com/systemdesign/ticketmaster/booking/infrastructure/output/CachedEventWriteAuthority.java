package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Positive-only per-event cache for regional booking authority.
 *
 * <p>This removes the low-write control plane from the hot booking path. It is deliberately
 * not a fencing mechanism: controlled regional failover must make the old regional writer
 * incapable of mutating authoritative inventory before ownership is transferred.</p>
 */
public final class CachedEventWriteAuthority implements EventWriteAuthority {
    private final EventWriteAuthority delegate;
    private final Clock clock;
    private final Duration ttl;
    private final ConcurrentMap<EventId, Permit> permits = new ConcurrentHashMap<>();

    public CachedEventWriteAuthority(EventWriteAuthority delegate, Clock clock, Duration ttl) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    @Override
    public void assertMayWrite(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");
        Instant now = clock.instant();
        Permit cached = permits.get(eventId);
        if (cached != null && cached.validAt(now)) {
            return;
        }

        permits.compute(eventId, (ignored, existing) -> {
            Instant checkTime = clock.instant();
            if (existing != null && existing.validAt(checkTime)) {
                return existing;
            }
            delegate.assertMayWrite(eventId);
            return new Permit(checkTime.plus(ttl));
        });
    }

    private record Permit(Instant expiresAt) {
        private Permit {
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        boolean validAt(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
