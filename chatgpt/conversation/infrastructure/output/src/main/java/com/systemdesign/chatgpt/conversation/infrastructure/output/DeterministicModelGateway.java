package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelEndpoint;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class DeterministicModelGateway implements ModelEndpoint {
    private static final ModelProfile PROFILE = new ModelProfile(
            "local-deterministic",
            Set.of(ModelCapability.TEXT_GENERATION, ModelCapability.STREAMING),
            0,
            0);

    @Override
    public ModelProfile profile() {
        return PROFILE;
    }

    @Override
    public boolean healthy() {
        return true;
    }

    @Override
    public Completion complete(List<Message> messages) {
        String userContent = latestUserContent(messages);
        return new Completion(PROFILE.model(), "assistant: " + userContent);
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
