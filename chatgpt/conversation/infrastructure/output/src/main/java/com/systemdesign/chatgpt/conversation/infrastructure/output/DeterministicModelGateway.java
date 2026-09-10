package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;

import java.util.List;
import java.util.function.Consumer;

public final class DeterministicModelGateway implements ModelGateway {
    @Override
    public Completion complete(List<Message> messages) {
        String userContent = latestUserContent(messages);
        return new Completion("local-deterministic", "assistant: " + userContent);
    }

    @Override
    public Completion stream(List<Message> messages, Consumer<String> deltaConsumer) {
        Completion completion = complete(messages);
        String content = completion.content();
        int split = Math.min(content.length(), "assistant: ".length());
        if (split > 0) {
            deltaConsumer.accept(content.substring(0, split));
        }
        if (split < content.length()) {
            deltaConsumer.accept(content.substring(split));
        }
        return completion;
    }

    private String latestUserContent(List<Message> messages) {
        return messages.reversed().stream()
                .filter(message -> message.role() == MessageRole.USER)
                .findFirst()
                .map(Message::content)
                .orElseThrow(() -> new IllegalArgumentException("a user message is required before inference"));
    }
}
