package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelEndpoint;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;
import com.systemdesign.chatgpt.conversation.domain.ModelProviderException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingModelGatewayFailureSemanticsTest {
    private final List<Message> messages = List.of(
            new Message(UUID.randomUUID(), MessageRole.USER, "hello", Instant.parse("2026-09-11T20:00:00Z")));

    @Test
    void exhaustedPermanentProviderFailuresRemainPermanent() {
        RoutingModelGateway router = new RoutingModelGateway(List.of(
                failing("one", 1, new ModelProviderException("bad request", false, 400)),
                failing("two", 2, new ModelProviderException("unauthorized", false, 401))));

        assertThatThrownBy(() -> router.complete(messages))
                .isInstanceOf(ModelUnavailableException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((ModelUnavailableException) error).retryable()).isFalse());
    }

    @Test
    void anyRetryableCandidateFailureKeepsExhaustedRouteRetryable() {
        RoutingModelGateway router = new RoutingModelGateway(List.of(
                failing("one", 1, new ModelProviderException("bad request", false, 400)),
                failing("two", 2, new ModelProviderException("rate limited", true, 429))));

        assertThatThrownBy(() -> router.complete(messages))
                .isInstanceOf(ModelUnavailableException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((ModelUnavailableException) error).retryable()).isTrue());
    }

    private static ModelEndpoint failing(String model, long cost, RuntimeException failure) {
        return new ModelEndpoint() {
            private final ModelProfile profile = new ModelProfile(model,
                    Set.of(ModelCapability.TEXT_GENERATION), cost, cost);
            @Override public ModelProfile profile() { return profile; }
            @Override public boolean healthy() { return true; }
            @Override public Completion complete(List<Message> messages) { throw failure; }
        };
    }
}
