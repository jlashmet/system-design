package com.systemdesign.ticketmaster.booking.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.infrastructure.input.HmacPaymentWebhookVerifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PaymentWebhookConfigurationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-29T01:15:00Z"), ZoneOffset.UTC);

    private BookingServiceApplication application;
    private HmacPaymentWebhookVerifier verifier;
    private Throwable thrown;

    @Test
    void webhookRequiresHttpPaymentMode() {
        givenApplication();
        whenWebhookVerifierBuilt("demo", "webhook-secret");
        thenExpectConfigurationFailure("payment webhook requires ticketmaster.booking.payment.mode=http");
    }

    @Test
    void webhookRequiresNonBlankSecret() {
        givenApplication();
        whenWebhookVerifierBuilt("http", " ");
        thenExpectConfigurationFailure("payment webhook secret must not be blank");
    }

    @Test
    void httpPaymentModeCanBuildWebhookVerifier() {
        givenApplication();
        whenWebhookVerifierBuilt("http", "webhook-secret");
        thenExpectVerifierBuilt();
    }

    private void givenApplication() {
        application = new BookingServiceApplication();
        verifier = null;
        thrown = null;
    }

    private void whenWebhookVerifierBuilt(String paymentMode, String secret) {
        try {
            verifier = application.paymentWebhookVerifier(CLOCK, paymentMode, secret, "PT5M");
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectConfigurationFailure(String expectedMessage) {
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage(expectedMessage);
        assertThat(verifier).isNull();
    }

    private void thenExpectVerifierBuilt() {
        assertThat(thrown).isNull();
        assertThat(verifier).isNotNull();
    }
}
