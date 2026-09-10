package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.application.ToolExecutor;
import com.systemdesign.chatgpt.conversation.domain.ToolAuthorization;
import com.systemdesign.chatgpt.conversation.domain.ToolHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
            @Value("${chatgpt.tools.timeout-ms:2000}") long timeoutMs,
            @Value("${chatgpt.tools.max-result-characters:16000}") int maxResultCharacters) {
        return new ToolExecutor(
                handlers.orderedStream().toList(),
                authorization,
                Duration.ofMillis(timeoutMs),
                maxResultCharacters);
    }
}
