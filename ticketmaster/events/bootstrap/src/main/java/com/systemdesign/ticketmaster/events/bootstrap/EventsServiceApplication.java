package com.systemdesign.ticketmaster.events.bootstrap;

import com.systemdesign.ticketmaster.events.application.BuildEventSearchProjectionHandler;
import com.systemdesign.ticketmaster.events.application.GetEventHandler;
import com.systemdesign.ticketmaster.events.domain.EventRepository;
import com.systemdesign.ticketmaster.events.domain.VenueRepository;
import com.systemdesign.ticketmaster.events.infrastructure.output.DynamoEventRepository;
import com.systemdesign.ticketmaster.events.infrastructure.output.DynamoVenueRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@SpringBootApplication(scanBasePackages = "com.systemdesign.ticketmaster.events.infrastructure.input")
public class EventsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EventsServiceApplication.class, args);
    }

    @Bean
    DynamoDbClient dynamoDbClient(@Value("${ticketmaster.aws.region:us-west-2}") String region) {
        return DynamoDbClient.builder().region(Region.of(region)).build();
    }

    @Bean
    EventRepository eventRepository(
            DynamoDbClient dynamoDbClient,
            @Value("${ticketmaster.events.table-name:ticketmaster-events}") String tableName) {
        return new DynamoEventRepository(dynamoDbClient, tableName);
    }

    @Bean
    VenueRepository venueRepository(
            DynamoDbClient dynamoDbClient,
            @Value("${ticketmaster.events.table-name:ticketmaster-events}") String tableName) {
        return new DynamoVenueRepository(dynamoDbClient, tableName);
    }

    @Bean
    GetEventHandler getEventHandler(EventRepository eventRepository) {
        return new GetEventHandler(eventRepository);
    }

    @Bean
    BuildEventSearchProjectionHandler buildEventSearchProjectionHandler(
            EventRepository eventRepository,
            VenueRepository venueRepository) {
        return new BuildEventSearchProjectionHandler(eventRepository, venueRepository);
    }
}
