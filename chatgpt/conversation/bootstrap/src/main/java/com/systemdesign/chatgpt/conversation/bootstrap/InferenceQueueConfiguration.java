package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryInferenceJobQueue;
import com.systemdesign.chatgpt.conversation.infrastructure.output.SqsInferenceJobQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
public class InferenceQueueConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "chatgpt.inference.queue-mode", havingValue = "memory", matchIfMissing = true)
    static class InMemoryQueueConfiguration {
        @Bean
        InferenceJobQueue inferenceJobQueue(
                @Value("${chatgpt.inference.queue-capacity:1024}") int capacity) {
            return new InMemoryInferenceJobQueue(capacity);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "chatgpt.inference.queue-mode", havingValue = "sqs")
    static class SqsQueueConfiguration {
        @Bean(destroyMethod = "close")
        SqsClient inferenceSqsClient(
                @Value("${chatgpt.inference.sqs.region:us-east-1}") String region,
                @Value("${chatgpt.inference.sqs.endpoint:}") String endpoint) {
            SqsClientBuilder builder = SqsClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(DefaultCredentialsProvider.create());
            if (endpoint != null && !endpoint.isBlank()) {
                builder.endpointOverride(URI.create(endpoint));
            }
            return builder.build();
        }

        @Bean
        InferenceJobQueue inferenceJobQueue(
                SqsClient inferenceSqsClient,
                @Value("${chatgpt.inference.sqs.queue-url}") String queueUrl,
                @Value("${chatgpt.inference.sqs.dead-letter-queue-url}") String deadLetterQueueUrl,
                @Value("${chatgpt.inference.sqs.visibility-timeout-seconds:60}") int visibilityTimeoutSeconds,
                @Value("${chatgpt.inference.sqs.wait-time-seconds:10}") int waitTimeSeconds) {
            return new SqsInferenceJobQueue(
                    inferenceSqsClient,
                    queueUrl,
                    deadLetterQueueUrl,
                    visibilityTimeoutSeconds,
                    waitTimeSeconds);
        }
    }
}
