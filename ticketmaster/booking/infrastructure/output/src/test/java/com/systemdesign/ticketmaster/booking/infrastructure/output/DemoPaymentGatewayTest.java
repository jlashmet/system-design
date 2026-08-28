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

    @Test
    void demoControlCanMoveCreatedIntentToSuccess() {
        DemoPaymentGateway gateway = new DemoPaymentGateway();
        BookingId bookingId = new BookingId("booking-1");
        PaymentIntent intent = gateway.createPaymentIntent(bookingId, PRICE, bookingId.value());

        PaymentIntentStatus status = gateway.succeedPayment(bookingId);

        assertThat(status).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        assertThat(gateway.getPaymentStatus(intent.id())).isEqualTo(PaymentIntentStatus.SUCCEEDED);
    }

    @Test
    void canceledIntentCannotBeResurrectedByDemoControl() {
        DemoPaymentGateway gateway = new DemoPaymentGateway();
        BookingId bookingId = new BookingId("booking-2");
        PaymentIntent intent = gateway.createPaymentIntent(bookingId, PRICE, bookingId.value());
        gateway.cancelPaymentIntent(intent.id());

        assertThatThrownBy(() -> gateway.succeedPayment(bookingId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already terminal");
    }
}
