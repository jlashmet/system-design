package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.application.DispatchInferenceOutboxHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class InferenceOutboxDispatcher {
    private final DispatchInferenceOutboxHandler handler;

    public InferenceOutboxDispatcher(DispatchInferenceOutboxHandler handler) {
        this.handler = handler;
    }

    @Scheduled(fixedDelayString = "${chatgpt.inference.outbox-poll-delay-ms:10}")
    public void dispatch() {
        handler.dispatchOne();
    }
}
