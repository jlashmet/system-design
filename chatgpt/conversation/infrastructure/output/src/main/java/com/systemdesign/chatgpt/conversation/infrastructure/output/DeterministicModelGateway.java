package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;

import java.util.List;

public final class DeterministicModelGateway implements ModelGateway {
    @Override
    public Completion complete(List<Message> messages) {
        String userContent = messages.reversed().stream()
                .filter(message -> message.role() == MessageRole.USER)
                .findFirst()
                .map(Message::content)
                .orElseThrow(() -> new IllegalArgumentException("a user message is required before inference"));
        return new Completion("local-deterministic", "assistant: " + userContent);
    }
}
