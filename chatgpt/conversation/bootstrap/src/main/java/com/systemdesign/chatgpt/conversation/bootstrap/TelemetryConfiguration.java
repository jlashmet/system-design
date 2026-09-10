package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.domain.ConversationTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TelemetryConfiguration {
    @Bean
    ConversationTelemetry conversationTelemetry(MeterRegistry registry) {
        return new MicrometerConversationTelemetry(registry);
    }
}
