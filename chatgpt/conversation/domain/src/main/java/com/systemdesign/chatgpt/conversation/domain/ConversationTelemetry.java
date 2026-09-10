package com.systemdesign.chatgpt.conversation.domain;

import java.time.Duration;

public interface ConversationTelemetry {
    void generationStarted(Duration queueDelay);
    void firstToken(Duration timeToFirstToken);
    void modelRound(Duration latency, String outcome);
    void generationFinished(Duration endToEndLatency, String outcome);
    void toolInvocation(String toolName, Duration latency, String outcome);
    void inferenceDelivery(int attempt, String outcome);

    static ConversationTelemetry noop() {
        return new ConversationTelemetry() {
            @Override public void generationStarted(Duration queueDelay) { }
            @Override public void firstToken(Duration timeToFirstToken) { }
            @Override public void modelRound(Duration latency, String outcome) { }
            @Override public void generationFinished(Duration endToEndLatency, String outcome) { }
            @Override public void toolInvocation(String toolName, Duration latency, String outcome) { }
            @Override public void inferenceDelivery(int attempt, String outcome) { }
        };
    }
}
