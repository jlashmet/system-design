package com.systemdesign.ticketmaster.controlplane.bootstrap;

import com.systemdesign.ticketmaster.controlplane.application.AssignEventOwnershipHandler;
import com.systemdesign.ticketmaster.controlplane.application.GetEventOwnershipHandler;
import com.systemdesign.ticketmaster.controlplane.application.TransferEventOwnershipHandler;
import com.systemdesign.ticketmaster.controlplane.domain.EventOwnershipRepository;
import com.systemdesign.ticketmaster.controlplane.domain.EventWriterFence;
import com.systemdesign.ticketmaster.controlplane.infrastructure.output.DynamoEventOwnershipRepository;
import com.systemdesign.ticketmaster.controlplane.infrastructure.output.FailClosedEventWriterFence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@SpringBootApplication(scanBasePackages = "com.systemdesign.ticketmaster.controlplane.infrastructure.input")
public class ControlPlaneApplication {
    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }

    @Bean
    DynamoDbClient dynamoDbClient(@Value("${ticketmaster.aws.region:us-west-2}") String region) {
        return DynamoDbClient.builder().region(Region.of(region)).build();
    }

    @Bean
    EventOwnershipRepository eventOwnershipRepository(
            DynamoDbClient dynamoDbClient,
            @Value("${ticketmaster.controlplane.table-name:ticketmaster-event-ownership}") String tableName) {
        return new DynamoEventOwnershipRepository(dynamoDbClient, tableName);
    }

    @Bean
    EventWriterFence eventWriterFence() {
        return new FailClosedEventWriterFence();
    }

    @Bean
    AssignEventOwnershipHandler assignEventOwnershipHandler(EventOwnershipRepository repository) {
        return new AssignEventOwnershipHandler(repository);
    }

    @Bean
    GetEventOwnershipHandler getEventOwnershipHandler(EventOwnershipRepository repository) {
        return new GetEventOwnershipHandler(repository);
    }

    @Bean
    TransferEventOwnershipHandler transferEventOwnershipHandler(
            EventOwnershipRepository repository,
            EventWriterFence writerFence) {
        return new TransferEventOwnershipHandler(repository, writerFence);
    }
}
