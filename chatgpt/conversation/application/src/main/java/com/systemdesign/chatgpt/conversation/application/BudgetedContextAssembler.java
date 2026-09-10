package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ContextAssembler;
import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.TokenEstimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class BudgetedContextAssembler implements ContextAssembler {
    private final TokenEstimator tokenEstimator;
    private final int maxInputTokens;

    public BudgetedContextAssembler(TokenEstimator tokenEstimator, int maxInputTokens) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        if (maxInputTokens < 1) {
            throw new IllegalArgumentException("maxInputTokens must be >= 1");
        }
        this.maxInputTokens = maxInputTokens;
    }

    @Override
    public List<Message> assemble(Conversation conversation, Generation generation) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(generation, "generation");
        if (!conversation.id().equals(generation.conversationId())) {
            throw new IllegalArgumentException("generation does not belong to conversation");
        }

        List<Message> messages = conversation.messages();
        int targetIndex = indexOf(messages, generation.userMessageId());
        List<Message> eligible = messages.subList(0, targetIndex + 1);

        List<Message> systemMessages = eligible.stream()
                .filter(message -> message.role() == MessageRole.SYSTEM)
                .toList();
        int usedTokens = tokenCount(systemMessages);
        if (usedTokens > maxInputTokens) {
            throw new ContextWindowExceededException("system context exceeds model input budget");
        }

        Message target = eligible.get(targetIndex);
        int targetTokens = tokenEstimator.estimateTokens(target);
        if (target.role() != MessageRole.SYSTEM && usedTokens + targetTokens > maxInputTokens) {
            throw new ContextWindowExceededException("current user message exceeds model input budget");
        }

        List<Message> recent = new ArrayList<>();
        for (int index = targetIndex; index >= 0; index--) {
            Message message = eligible.get(index);
            if (message.role() == MessageRole.SYSTEM) {
                continue;
            }
            int tokens = tokenEstimator.estimateTokens(message);
            if (usedTokens + tokens > maxInputTokens) {
                break;
            }
            recent.add(message);
            usedTokens += tokens;
        }
        Collections.reverse(recent);

        List<Message> assembled = new ArrayList<>(systemMessages.size() + recent.size());
        assembled.addAll(systemMessages);
        assembled.addAll(recent);
        return List.copyOf(assembled);
    }

    private int indexOf(List<Message> messages, java.util.UUID messageId) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).id().equals(messageId)) {
                return index;
            }
        }
        throw new IllegalStateException("generation references missing user message: " + messageId);
    }

    private int tokenCount(List<Message> messages) {
        return messages.stream().mapToInt(tokenEstimator::estimateTokens).sum();
    }
}
