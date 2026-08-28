package com.systemdesign.ticketmaster.booking.bootstrap;

import com.systemdesign.ticketmaster.booking.application.CheckAdmissionHandler;
import com.systemdesign.ticketmaster.booking.application.CreateHoldHandler;
import com.systemdesign.ticketmaster.booking.application.EnableAdmissionHandler;
import com.systemdesign.ticketmaster.booking.application.GetSectionSeatsHandler;
import com.systemdesign.ticketmaster.booking.application.GetSectionsHandler;
import com.systemdesign.ticketmaster.booking.application.JoinWaitingRoomHandler;
import com.systemdesign.ticketmaster.booking.application.ProjectSeatMapHandler;
import com.systemdesign.ticketmaster.booking.application.ReconcileBookingHandler;
import com.systemdesign.ticketmaster.booking.application.ReconcileDueBookingsHandler;
import com.systemdesign.ticketmaster.booking.application.RegulateAdmissionHandler;
import com.systemdesign.ticketmaster.booking.application.StartCheckoutHandler;
import com.systemdesign.ticketmaster.booking.domain.AdmissionCapacity;
import com.systemdesign.ticketmaster.booking.domain.AdmissionHealthGateway;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import com.systemdesign.ticketmaster.booking.domain.CheckoutGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.EventWriteAuthority;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.PaymentGateway;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import com.systemdesign.ticketmaster.booking.infrastructure.input.DynamoSeatInventoryStreamProjector;
import com.systemdesign.ticketmaster.booking.infrastructure.output.CachedEventWriteAuthority;
import com.systemdesign.ticketmaster.booking.infrastructure.output.ConfiguredAdmissionHealthGateway;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DemoPaymentGateway;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoBookingRepository;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoCheckoutGateway;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoHoldRepository;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoSeatMapRepository;
import com.systemdesign.ticketmaster.booking.infrastructure.output.DynamoWaitingRoomRepository;
import com.systemdesign.ticketmaster.booking.infrastructure.output.HttpEventWriteAuthority;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@EnableScheduling
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
    EventWriteAuthority eventWriteAuthority(
            Clock clock,
            @Value("${ticketmaster.controlplane.base-url:http://localhost:8083}") String controlPlaneBaseUrl,
            @Value("${ticketmaster.aws.region:us-west-2}") String localRegion,
            @Value("${ticketmaster.controlplane.request-timeout:PT1S}") String requestTimeout,
            @Value("${ticketmaster.controlplane.ownership-cache-ttl:PT5S}") String ownershipCacheTtl) {
        Duration timeout = Duration.parse(requestTimeout);
        EventWriteAuthority remoteAuthority = new HttpEventWriteAuthority(
                HttpClient.newBuilder().connectTimeout(timeout).build(),
                URI.create(controlPlaneBaseUrl),
                localRegion,
                timeout);
        return new CachedEventWriteAuthority(remoteAuthority, clock, Duration.parse(ownershipCacheTtl));
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
    DemoPaymentGateway paymentGateway() {
        return new DemoPaymentGateway();
    }

    @Bean
    AdmissionHealthGateway admissionHealthGateway(
            @Value("${ticketmaster.booking.admission.capacity:OVERLOADED}") String capacity) {
        return new ConfiguredAdmissionHealthGateway(
                AdmissionCapacity.valueOf(capacity.trim().toUpperCase(Locale.ROOT)));
    }

    @Bean
    CreateHoldHandler createHoldHandler(
            EventWriteAuthority eventWriteAuthority,
            HoldRepository holdRepository,
            WaitingRoomRepository waitingRoomRepository,
            Clock clock,
            @Value("${ticketmaster.booking.hold-duration:PT5M}") String holdDuration) {
        return new CreateHoldHandler(
                eventWriteAuthority,
                holdRepository,
                waitingRoomRepository,
                clock,
                Duration.parse(holdDuration));
    }

    @Bean
    StartCheckoutHandler startCheckoutHandler(
            EventWriteAuthority eventWriteAuthority,
            HoldRepository holdRepository,
            BookingRepository bookingRepository,
            CheckoutGateway checkoutGateway,
            PaymentGateway paymentGateway,
            Clock clock,
            @Value("${ticketmaster.booking.checkout-duration:PT10M}") String checkoutDuration,
            @Value("${ticketmaster.booking.reconciliation-delay:PT30S}") String reconciliationDelay,
            @Value("${ticketmaster.booking.reconciliation-shards:16}") int reconciliationShards) {
        return new StartCheckoutHandler(
                eventWriteAuthority,
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
    ReconcileBookingHandler reconcileBookingHandler(
            EventWriteAuthority eventWriteAuthority,
            BookingRepository bookingRepository,
            HoldRepository holdRepository,
            CheckoutGateway checkoutGateway,
            PaymentGateway paymentGateway,
            Clock clock,
            @Value("${ticketmaster.booking.reconciliation-backoff:PT30S}") String reconciliationBackoff) {
        return new ReconcileBookingHandler(
                eventWriteAuthority,
                bookingRepository,
                holdRepository,
                checkoutGateway,
                paymentGateway,
                clock,
                Duration.parse(reconciliationBackoff));
    }

    @Bean
    @ConditionalOnProperty(
            name = "ticketmaster.booking.demo-payment-endpoint-enabled",
            havingValue = "true")
    DemoPaymentController demoPaymentController(
            DemoPaymentGateway paymentGateway,
            ReconcileBookingHandler reconcileBookingHandler) {
        return new DemoPaymentController(paymentGateway, reconcileBookingHandler);
    }

    @Bean
    ReconcileDueBookingsHandler reconcileDueBookingsHandler(
            BookingRepository bookingRepository,
            ReconcileBookingHandler reconcileBookingHandler,
            Clock clock,
            @Value("${ticketmaster.booking.reconciliation-shards:16}") int reconciliationShards,
            @Value("${ticketmaster.booking.reconciliation-batch-size-per-shard:25}") int batchSizePerShard) {
        return new ReconcileDueBookingsHandler(
                bookingRepository,
                reconcileBookingHandler,
                clock,
                reconciliationShards,
                batchSizePerShard);
    }

    @Bean
    BookingReconciliationScheduler bookingReconciliationScheduler(ReconcileDueBookingsHandler handler) {
        return new BookingReconciliationScheduler(handler);
    }

    @Bean
    EnableAdmissionHandler enableAdmissionHandler(WaitingRoomRepository waitingRoomRepository) {
        return new EnableAdmissionHandler(waitingRoomRepository);
    }

    @Bean
    RegulateAdmissionHandler regulateAdmissionHandler(
            WaitingRoomRepository waitingRoomRepository,
            AdmissionHealthGateway admissionHealthGateway,
            Clock clock,
            @Value("${ticketmaster.booking.admission.healthy-advance:PT2S}") String healthyAdvance,
            @Value("${ticketmaster.booking.admission.constrained-advance:PT0.5S}") String constrainedAdvance) {
        return new RegulateAdmissionHandler(
                waitingRoomRepository,
                admissionHealthGateway,
                clock,
                Duration.parse(healthyAdvance),
                Duration.parse(constrainedAdvance));
    }

    @Bean
    AdmissionRegulationScheduler admissionRegulationScheduler(
            EnableAdmissionHandler enableAdmissionHandler,
            RegulateAdmissionHandler handler,
            @Value("${ticketmaster.booking.admission.event-ids:}") String configuredEventIds) {
        return new AdmissionRegulationScheduler(
                enableAdmissionHandler,
                handler,
                parseEventIds(configuredEventIds));
    }

    @Bean
    ProjectSeatMapHandler projectSeatMapHandler(SeatMapRepository seatMapRepository) {
        return new ProjectSeatMapHandler(seatMapRepository);
    }

    @Bean
    DynamoSeatInventoryStreamProjector dynamoSeatInventoryStreamProjector(ProjectSeatMapHandler handler) {
        return new DynamoSeatInventoryStreamProjector(handler);
    }

    @Bean
    GetSectionsHandler getSectionsHandler(SeatMapRepository seatMapRepository) {
        return new GetSectionsHandler(seatMapRepository);
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

    private static List<EventId> parseEventIds(String configuredEventIds) {
        if (configuredEventIds == null || configuredEventIds.isBlank()) return List.of();
        return Arrays.stream(configuredEventIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .map(EventId::new)
                .toList();
    }
}
