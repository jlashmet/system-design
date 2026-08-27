package com.systemdesign.ticketmaster.booking.bootstrap;

import com.systemdesign.ticketmaster.booking.application.CheckAdmissionHandler;
import com.systemdesign.ticketmaster.booking.application.CreateHoldHandler;
import com.systemdesign.ticketmaster.booking.application.GetSectionSeatsHandler;
import com.systemdesign.ticketmaster.booking.application.JoinWaitingRoomHandler;
import com.systemdesign.ticketmaster.booking.application.StartCheckoutHandler;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DemoPaymentGateway;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoBookingRepository;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoCheckoutGateway;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoHoldRepository;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoSeatMapRepository;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoWaitingRoomRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@SpringBootApplication(scanBasePackages = "com.systemdesign.ticketmaster.booking.infrastructure.input")
public class BookingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    DynamoDbClient dynamoDbClient(@Value("${ticketmaster.aws.region:us-west-2}") String region) {
        return DynamoDbClient.builder().region(Region.of(region)).build();
    }

    @Bean
    HoldRepository holdRepository(
            DynamoDbClient dynamoDbClient,
            @Value("${ticketmaster.booking.table-name:ticketmaster-booking}") String tableName) {
        return new DynamoHoldRepository(dynamoDbClient, tableName);
    }

    @Bean
    BookingRepository bookingRepository(
            DynamoDbClient dynamoDbClient,
            @Value("${ticketmaster.booking.table-name:ticketmaster-booking}") String tableName) {
        return new DynamoBookingRepository(dynamoDbClient, tableName);
    }

    @Bean
    CheckoutGateway checkoutGateway(
            DynamoDbClient dynamoDbClient,
            @Value("${ticketmaster.booking.table-name:ticketmaster-booking}") String tableName) {
        return new DynamoCheckoutGateway(dynamoDbClient, tableName);
    }

    @Bean
    SeatMapRepository seatMapRepository(
            DynamoDbClient dynamoDbClient,
            @Value("${ticketmaster.booking.seat-map-table-name:ticketmaster-seat-map}") String tableName) {
        return new DynamoSeatMapRepository(dynamoDbClient, tableName);
    }

    @Bean
    WaitingRoomRepository waitingRoomRepository(
            DynamoDbClient dynamoDbClient,
            @Value("${ticketmaster.booking.table-name:ticketmaster-booking}") String tableName) {
        return new DynamoWaitingRoomRepository(dynamoDbClient, tableName);
    }

    @Bean
    PaymentGateway paymentGateway() {
        return new DemoPaymentGateway();
    }

    @Bean
    CreateHoldHandler createHoldHandler(
            HoldRepository holdRepository,
            Clock clock,
            @Value("${ticketmaster.booking.hold-duration:PT5M}") String holdDuration) {
        return new CreateHoldHandler(holdRepository, clock, Duration.parse(holdDuration));
    }

    @Bean
    StartCheckoutHandler startCheckoutHandler(
            HoldRepository holdRepository,
            BookingRepository bookingRepository,
            CheckoutGateway checkoutGateway,
            PaymentGateway paymentGateway,
            Clock clock,
            @Value("${ticketmaster.booking.checkout-duration:PT10M}") String checkoutDuration,
            @Value("${ticketmaster.booking.reconciliation-delay:PT30S}") String reconciliationDelay,
            @Value("${ticketmaster.booking.reconciliation-shards:16}") int reconciliationShards) {
        return new StartCheckoutHandler(
                holdRepository,
                bookingRepository,
                checkoutGateway,
                paymentGateway,
                clock,
                Duration.parse(checkoutDuration),
                Duration.parse(reconciliationDelay),
                reconciliationShards);
    }

    @Bean
    GetSectionSeatsHandler getSectionSeatsHandler(SeatMapRepository seatMapRepository) {
        return new GetSectionSeatsHandler(seatMapRepository);
    }

    @Bean
    JoinWaitingRoomHandler joinWaitingRoomHandler(WaitingRoomRepository waitingRoomRepository, Clock clock) {
        return new JoinWaitingRoomHandler(waitingRoomRepository, clock);
    }

    @Bean
    CheckAdmissionHandler checkAdmissionHandler(WaitingRoomRepository waitingRoomRepository) {
        return new CheckAdmissionHandler(waitingRoomRepository);
    }
}
