package com.systemdesign.ticketmaster.booking.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.AdmissionGrantService;
import com.systemdesign.ticketmaster.booking.infrastructure.output.HmacAdmissionGrantService;
import org.junit.jupiter.api.Test;

class AdmissionGrantConfigurationTest {
    private BookingServiceApplication application;
    private AdmissionGrantService service;
    private Throwable thrown;

    @Test
    void disabledModeProvidesNoOpGrantService() {
        givenApplication();
        whenDisabledGrantServiceBuilt();
        thenExpectDisabledService();
    }

    @Test
    void hmacModeBuildsSignedGrantService() {
        givenApplication();
        whenHmacGrantServiceBuilt("grant-secret", "PT30S");
        thenExpectServiceType(HmacAdmissionGrantService.class);
    }

    @Test
    void hmacModeRejectsBlankSecret() {
        givenApplication();
        whenHmacGrantServiceBuilt("", "PT30S");
        thenExpectConfigurationFailure(IllegalStateException.class, "admission grant secret must not be blank");
    }

    @Test
    void hmacModeRejectsNonPositiveTtl() {
        givenApplication();
        whenHmacGrantServiceBuilt("grant-secret", "PT0S");
        thenExpectConfigurationFailure(IllegalArgumentException.class, "ttl must be positive");
    }

    private void givenApplication() {
        application = new BookingServiceApplication();
        service = null;
        thrown = null;
    }

    private void whenDisabledGrantServiceBuilt() {
        service = application.admissionGrantService();
    }

    private void whenHmacGrantServiceBuilt(String secret, String ttl) {
        try {
            service = application.hmacAdmissionGrantService(secret, ttl);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectDisabledService() {
        assertThat(thrown).isNull();
        assertThat(service).isNotNull();
        assertThat(service.issue(
                new com.systemdesign.ticketmaster.booking.domain.EventId("event-1"),
                new com.systemdesign.ticketmaster.booking.domain.UserId("user-1"),
                java.time.Instant.EPOCH)).isEmpty();
    }

    private void thenExpectServiceType(Class<? extends AdmissionGrantService> expectedType) {
        assertThat(thrown).isNull();
        assertThat(service).isInstanceOf(expectedType);
    }

    private void thenExpectConfigurationFailure(Class<? extends Throwable> type, String message) {
        assertThat(thrown).isInstanceOf(type).hasMessage(message);
        assertThat(service).isNull();
    }
}
