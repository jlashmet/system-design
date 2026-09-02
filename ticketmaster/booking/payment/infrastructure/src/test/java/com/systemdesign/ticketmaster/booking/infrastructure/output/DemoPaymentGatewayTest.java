package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.systemdesign.ticketmaster.booking.domain.BookingId;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntentStatus;
import com.systemdesign.ticketmaster.booking.domain.Price;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class DemoPaymentGatewayTest {
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    private DemoPaymentGateway gateway;
    private BookingId bookingId;
    private PaymentIntent intent;
    private PaymentIntentStatus status;
    private Throwable thrown;

    @Test
    void demoControlCanMoveCreatedIntentToSuccess() {
        givenCreatedPayment("booking-1");
        whenPaymentSucceeds();
        thenExpectSucceededIntent();
    }

    @Test
    void canceledIntentCannotBeResurrectedByDemoControl() {
        givenCanceledPayment("booking-2");
        whenPaymentSucceedIsRetried();
        thenExpectTerminalStateRejected();
    }

    private void givenCreatedPayment(String id) {
        gateway = new DemoPaymentGateway();
        bookingId = new BookingId(id);
        intent = gateway.createPaymentIntent(bookingId, PRICE, bookingId.value());
        status = null;
        thrown = null;
    }

    private void givenCanceledPayment(String id) {
        givenCreatedPayment(id);
        gateway.cancelPaymentIntent(intent.id());
    }

    private void whenPaymentSucceeds() {
        status = gateway.succeedPayment(bookingId);
    }

    private void whenPaymentSucceedIsRetried() {
        try {
            gateway.succeedPayment(bookingId);
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectSucceededIntent() {
        assertThat(status).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        assertThat(gateway.getPaymentStatus(intent.id())).isEqualTo(PaymentIntentStatus.SUCCEEDED);
    }

    private void thenExpectTerminalStateRejected() {
        assertThatThrownBy(() -> {
            if (thrown != null) throw thrown;
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already terminal");
    }
}
