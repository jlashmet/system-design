package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelEndpoint;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway.Completion;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class HealthCachingModelEndpointTest {
    @Test
    void reusesHealthWithinTtlAndRefreshesAfterExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-11T20:00:00Z"));
        AtomicInteger probes = new AtomicInteger();
        HealthCachingModelEndpoint endpoint = new HealthCachingModelEndpoint(
                endpoint(() -> { probes.incrementAndGet(); return true; }), Duration.ofSeconds(5), clock);

        assertThat(endpoint.healthy()).isTrue();
        assertThat(endpoint.healthy()).isTrue();
        assertThat(probes).hasValue(1);

        clock.advance(Duration.ofSeconds(5));
        assertThat(endpoint.healthy()).isTrue();
        assertThat(probes).hasValue(2);
    }

    @Test
    void cachesFailedAndThrowingHealthChecksAsUnhealthy() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-11T20:00:00Z"));
        AtomicInteger probes = new AtomicInteger();
        HealthCachingModelEndpoint endpoint = new HealthCachingModelEndpoint(
                endpoint(() -> {
                    if (probes.incrementAndGet() == 1) throw new IllegalStateException("probe failed");
                    return true;
                }), Duration.ofSeconds(2), clock);

        assertThat(endpoint.healthy()).isFalse();
        assertThat(endpoint.healthy()).isFalse();
        assertThat(probes).hasValue(1);

        clock.advance(Duration.ofSeconds(2));
        assertThat(endpoint.healthy()).isTrue();
        assertThat(probes).hasValue(2);
    }

    private static ModelEndpoint endpoint(BooleanSupplier health) {
        return new ModelEndpoint() {
            private final ModelProfile profile = new ModelProfile(
                    "test", Set.of(ModelCapability.TEXT_GENERATION), 0, 0);

            @Override public ModelProfile profile() { return profile; }
            @Override public boolean healthy() { return health.getAsBoolean(); }
            @Override public Completion complete(List<Message> messages) { return new Completion("test", "ok"); }
        };
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
