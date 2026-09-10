package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.application.ToolExecutor;
import com.systemdesign.chatgpt.conversation.domain.ConversationTelemetry;
import com.systemdesign.chatgpt.conversation.domain.ToolAuthorization;
import com.systemdesign.chatgpt.conversation.domain.ToolHandler;
import com.systemdesign.chatgpt.conversation.domain.ToolInvocationStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ToolConfiguration {
    @Bean
    @ConditionalOnMissingBean(ToolAuthorization.class)
    ToolAuthorization toolAuthorization() {
        return (userId, conversationId, toolName) -> false;
    }

    @Bean
    ToolExecutor toolExecutor(
            ObjectProvider<ToolHandler> handlers,
            ToolAuthorization authorization,
            ToolInvocationStore invocationStore,
            ConversationTelemetry telemetry,
            Clock clock,
            @Value("${chatgpt.tools.timeout-ms:2000}") long timeoutMs,
            @Value("${chatgpt.tools.invocation-lease-ms:10000}") long invocationLeaseMs,
            @Value("${chatgpt.tools.max-result-characters:16000}") int maxResultCharacters) {
        return new ToolExecutor(
                handlers.orderedStream().toList(), authorization, invocationStore, telemetry, clock,
                Duration.ofMillis(timeoutMs), Duration.ofMillis(invocationLeaseMs), maxResultCharacters);
    }
}
