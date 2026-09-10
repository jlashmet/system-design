package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.domain.ConversationMessagePageStore;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryStore;
import com.systemdesign.chatgpt.conversation.domain.GenerationContinuationStore;
import com.systemdesign.chatgpt.conversation.domain.InferenceQuota;
import com.systemdesign.chatgpt.conversation.domain.LongTermMemoryStore;
import com.systemdesign.chatgpt.conversation.domain.RunningMessageStore;
import com.systemdesign.chatgpt.conversation.domain.ToolInvocationStore;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import com.systemdesign.chatgpt.conversation.infrastructure.output.DynamoConversationMessagePageStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.DynamoConversationSummaryStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.DynamoConversationTurnStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.DynamoFixedWindowInferenceQuota;
import com.systemdesign.chatgpt.conversation.infrastructure.output.DynamoLongTermMemoryStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.DynamoRunningMessageStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.DynamoToolInvocationStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryConversationMessagePageStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryConversationRepository;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryConversationSummaryStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryFixedWindowInferenceQuota;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryLongTermMemoryStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryToolInvocationStore;
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
public class StorageConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "chatgpt.storage.mode", havingValue = "memory", matchIfMissing = true)
    static class InMemoryStorageConfiguration {
        @Bean InMemoryConversationRepository conversationStore() { return new InMemoryConversationRepository(); }
        @Bean ConversationRepository conversationRepository(InMemoryConversationRepository store) { return store; }
        @Bean TurnRepository turnRepository(InMemoryConversationRepository store) { return store; }
        @Bean RunningMessageStore runningMessageStore(InMemoryConversationRepository store) { return store; }
        @Bean GenerationContinuationStore generationContinuationStore(InMemoryConversationRepository store) { return store; }
        @Bean ConversationMessagePageStore conversationMessagePageStore(ConversationRepository repository) {
            return new InMemoryConversationMessagePageStore(repository);
        }
        @Bean ToolInvocationStore toolInvocationStore() { return new InMemoryToolInvocationStore(); }
        @Bean ConversationSummaryStore conversationSummaryStore() { return new InMemoryConversationSummaryStore(); }
        @Bean LongTermMemoryStore longTermMemoryStore() { return new InMemoryLongTermMemoryStore(); }
        @Bean InferenceQuota inferenceQuota(@Value("${chatgpt.inference.requests-per-minute:60}") int maxRequests) {
            return new InMemoryFixedWindowInferenceQuota(maxRequests, Duration.ofMinutes(1));
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
                    .region(Region.of(region)).credentialsProvider(DefaultCredentialsProvider.create());
            if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
            return builder.build();
        }

        @Bean
        DynamoConversationTurnStore conversationStore(DynamoDbClient conversationDynamoDbClient,
                @Value("${chatgpt.storage.dynamo.table-name:chatgpt-conversations}") String tableName) {
            return new DynamoConversationTurnStore(conversationDynamoDbClient, tableName);
        }
        @Bean ConversationRepository conversationRepository(DynamoConversationTurnStore store) { return store; }
        @Bean TurnRepository turnRepository(DynamoConversationTurnStore store) { return store; }
        @Bean DynamoRunningMessageStore runningMessageAdapter(DynamoDbClient conversationDynamoDbClient,
                @Value("${chatgpt.storage.dynamo.table-name:chatgpt-conversations}") String tableName) {
            return new DynamoRunningMessageStore(conversationDynamoDbClient, tableName);
        }
        @Bean RunningMessageStore runningMessageStore(DynamoRunningMessageStore store) { return store; }
        @Bean GenerationContinuationStore generationContinuationStore(DynamoRunningMessageStore store) { return store; }
        @Bean ConversationMessagePageStore conversationMessagePageStore(DynamoDbClient conversationDynamoDbClient,
                @Value("${chatgpt.storage.dynamo.table-name:chatgpt-conversations}") String tableName) {
            return new DynamoConversationMessagePageStore(conversationDynamoDbClient, tableName);
        }
        @Bean ToolInvocationStore toolInvocationStore(DynamoDbClient conversationDynamoDbClient,
                @Value("${chatgpt.storage.dynamo.table-name:chatgpt-conversations}") String tableName) {
            return new DynamoToolInvocationStore(conversationDynamoDbClient, tableName);
        }
        @Bean LongTermMemoryStore longTermMemoryStore(DynamoDbClient conversationDynamoDbClient,
                @Value("${chatgpt.storage.dynamo.table-name:chatgpt-conversations}") String tableName) {
            return new DynamoLongTermMemoryStore(conversationDynamoDbClient, tableName);
        }
        @Bean InferenceQuota inferenceQuota(DynamoDbClient conversationDynamoDbClient,
                @Value("${chatgpt.storage.dynamo.table-name:chatgpt-conversations}") String tableName,
                @Value("${chatgpt.inference.requests-per-minute:60}") int maxRequests) {
            return new DynamoFixedWindowInferenceQuota(conversationDynamoDbClient, tableName, maxRequests, Duration.ofMinutes(1));
        }
        @Bean ConversationSummaryStore conversationSummaryStore(DynamoDbClient conversationDynamoDbClient,
                @Value("${chatgpt.storage.dynamo.summary-table-name:chatgpt-conversation-summaries}") String tableName) {
            return new DynamoConversationSummaryStore(conversationDynamoDbClient, tableName);
        }
    }
}
