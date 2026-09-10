package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.domain.GenerationEventStore;
import com.systemdesign.chatgpt.conversation.domain.ReplayableGenerationEventBus;
import com.systemdesign.chatgpt.conversation.infrastructure.output.DynamoGenerationEventStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryGenerationEventBus;
import com.systemdesign.chatgpt.conversation.infrastructure.output.StoredGenerationEventBus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class GenerationEventConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "chatgpt.events.mode", havingValue = "memory", matchIfMissing = true)
    static class InMemoryEventConfiguration {
        @Bean
        ReplayableGenerationEventBus generationEventBus() {
            return new InMemoryGenerationEventBus();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "chatgpt.events.mode", havingValue = "dynamo")
    static class DynamoEventConfiguration {
        @Bean(destroyMethod = "close")
        DynamoDbClient generationEventDynamoDbClient(
                @Value("${chatgpt.events.dynamo.region:us-east-1}") String region,
                @Value("${chatgpt.events.dynamo.endpoint:}") String endpoint) {
            DynamoDbClientBuilder builder = DynamoDbClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(DefaultCredentialsProvider.create());
            if (endpoint != null && !endpoint.isBlank()) {
                builder.endpointOverride(URI.create(endpoint));
            }
            return builder.build();
        }

        @Bean
        GenerationEventStore generationEventStore(
                DynamoDbClient generationEventDynamoDbClient,
                @Value("${chatgpt.events.dynamo.table-name:chatgpt-generation-events}") String tableName) {
            return new DynamoGenerationEventStore(generationEventDynamoDbClient, tableName);
        }

        @Bean
        ReplayableGenerationEventBus generationEventBus(
                GenerationEventStore store,
                @Value("${chatgpt.events.poll-interval-ms:25}") long pollIntervalMs,
                @Value("${chatgpt.events.poll-batch-size:100}") int batchSize) {
            return new StoredGenerationEventBus(store, Duration.ofMillis(pollIntervalMs), batchSize);
        }
    }
}
