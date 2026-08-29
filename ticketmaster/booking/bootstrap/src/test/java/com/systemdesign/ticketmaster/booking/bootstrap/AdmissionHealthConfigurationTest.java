package com.systemdesign.ticketmaster.booking.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.AdmissionCapacity;
import com.systemdesign.ticketmaster.booking.domain.AdmissionHealthGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.infrastructure.output.ConfiguredAdmissionHealthGateway;
import com.systemdesign.ticketmaster.booking.infrastructure.output.HttpAdmissionHealthGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AdmissionHealthConfigurationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);

    private BookingServiceApplication application;
    private AdmissionHealthGateway gateway;
    private Throwable thrown;

    @Test
    void configuredModeRemainsFailClosedByDefaultCapacity() {
        givenApplication();
        whenConfiguredGatewayBuilt("OVERLOADED");
        thenExpectConfiguredCapacity(AdmissionCapacity.OVERLOADED);
    }

    @Test
    void httpModeBuildsTelemetryGateway() {
        givenApplication();
        whenHttpGatewayBuilt("http://127.0.0.1:9999", "PT0.5S", "PT3S", "PT1S");
        thenExpectGatewayType(HttpAdmissionHealthGateway.class);
    }

    @Test
    void httpModeRejectsNonPositiveFreshnessWindow() {
        givenApplication();
        whenHttpGatewayBuilt("http://127.0.0.1:9999", "PT0.5S", "PT0S", "PT1S");
        thenExpectConfigurationFailure("maxSignalAge must be positive");
    }

    private void givenApplication() {
        application = new BookingServiceApplication();
        gateway = null;
        thrown = null;
    }

    private void whenConfiguredGatewayBuilt(String capacity) {
        try {
            gateway = application.admissionHealthGateway(capacity);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void whenHttpGatewayBuilt(String baseUrl, String timeout, String maxSignalAge, String maxFutureSkew) {
        try {
            gateway = application.httpAdmissionHealthGateway(
                    CLOCK, baseUrl, timeout, maxSignalAge, maxFutureSkew);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectConfiguredCapacity(AdmissionCapacity expected) {
        assertThat(thrown).isNull();
        assertThat(gateway).isInstanceOf(ConfiguredAdmissionHealthGateway.class);
        assertThat(gateway.assess(new EventId("event-123"))).isEqualTo(expected);
    }

    private void thenExpectGatewayType(Class<? extends AdmissionHealthGateway> expectedType) {
        assertThat(thrown).isNull();
        assertThat(gateway).isInstanceOf(expectedType);
    }

    private void thenExpectConfigurationFailure(String expectedMessage) {
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage(expectedMessage);
        assertThat(gateway).isNull();
    }
}
