package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import com.systemdesign.chatgpt.conversation.infrastructure.output.DynamoConversationTurnStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryConversationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "chatgpt.storage.mode", havingValue = "memory", matchIfMissing = true)
    static class InMemoryStorageConfiguration {
        @Bean
        InMemoryConversationRepository conversationStore() {
            return new InMemoryConversationRepository();
        }

        @Bean
        ConversationRepository conversationRepository(InMemoryConversationRepository store) {
            return store;
        }

        @Bean
        TurnRepository turnRepository(InMemoryConversationRepository store) {
            return store;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "chatgpt.storage.mode", havingValue = "dynamo")
    static class DynamoStorageConfiguration {
        @Bean(destroyMethod = "close")
        DynamoDbClient conversationDynamoDbClient(
                @Value("${chatgpt.storage.dynamo.region:us-east-1}") String region,
                @Value("${chatgpt.storage.dynamo.endpoint:}") String endpoint) {
            DynamoDbClientBuilder builder = DynamoDbClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(DefaultCredentialsProvider.create());
            if (endpoint != null && !endpoint.isBlank()) {
                builder.endpointOverride(URI.create(endpoint));
            }
            return builder.build();
        }

        @Bean
        DynamoConversationTurnStore conversationStore(
                DynamoDbClient conversationDynamoDbClient,
                @Value("${chatgpt.storage.dynamo.table-name:chatgpt-conversations}") String tableName) {
            return new DynamoConversationTurnStore(conversationDynamoDbClient, tableName);
        }

        @Bean
        ConversationRepository conversationRepository(DynamoConversationTurnStore store) {
            return store;
        }

        @Bean
        TurnRepository turnRepository(DynamoConversationTurnStore store) {
            return store;
        }
    }
}
