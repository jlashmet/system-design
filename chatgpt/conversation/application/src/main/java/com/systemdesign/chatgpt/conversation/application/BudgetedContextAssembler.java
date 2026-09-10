package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ContextAssembler;
import com.systemdesign.chatgpt.conversation.domain.ContextSource;
import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationTelemetry;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationContinuationStore;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.TokenEstimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class BudgetedContextAssembler implements ContextAssembler {
    private final TokenEstimator tokenEstimator;
    private final int maxInputTokens;
    private final List<ContextSource> contextSources;
    private final GenerationContinuationStore continuationStore;
    private final ConversationTelemetry telemetry;

    public BudgetedContextAssembler(TokenEstimator tokenEstimator, int maxInputTokens) {
        this(tokenEstimator, maxInputTokens, List.of(), GenerationContinuationStore.empty(), ConversationTelemetry.noop());
    }

    public BudgetedContextAssembler(TokenEstimator tokenEstimator, int maxInputTokens, List<ContextSource> contextSources) {
        this(tokenEstimator, maxInputTokens, contextSources, GenerationContinuationStore.empty(), ConversationTelemetry.noop());
    }

    public BudgetedContextAssembler(TokenEstimator tokenEstimator, int maxInputTokens,
            List<ContextSource> contextSources, ConversationTelemetry telemetry) {
        this(tokenEstimator, maxInputTokens, contextSources, GenerationContinuationStore.empty(), telemetry);
    }

    public BudgetedContextAssembler(TokenEstimator tokenEstimator, int maxInputTokens,
            List<ContextSource> contextSources, GenerationContinuationStore continuationStore,
            ConversationTelemetry telemetry) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        this.continuationStore = Objects.requireNonNull(continuationStore, "continuationStore");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        if (maxInputTokens < 1) throw new IllegalArgumentException("maxInputTokens must be >= 1");
        this.maxInputTokens = maxInputTokens;
        this.contextSources = List.copyOf(Objects.requireNonNull(contextSources, "contextSources")).stream()
                .sorted(Comparator.comparingInt(ContextSource::priority)).toList();
    }

    @Override
    public List<Message> assemble(Conversation conversation, Generation generation) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(generation, "generation");
        if (!conversation.id().equals(generation.conversationId()))
            throw new IllegalArgumentException("generation does not belong to conversation");

        List<Message> messages = conversation.messages();
        int targetIndex = indexOf(messages, generation.userMessageId());
        List<Message> history = messages.subList(0, targetIndex + 1);
        List<Message> continuation = continuationStore.list(generation.id());

        List<Message> systemMessages = history.stream()
                .filter(message -> message.role() == MessageRole.SYSTEM)
                .toList();
        int systemTokens = tokenCount(systemMessages);
        int continuationTokens = tokenCount(continuation);
        int usedTokens = systemTokens + continuationTokens;
        if (usedTokens > maxInputTokens)
            throw new ContextWindowExceededException("required system and generation continuation context exceeds model input budget");

        Message target = history.get(targetIndex);
        int targetTokens = tokenEstimator.estimateTokens(target);
        if (target.role() != MessageRole.SYSTEM && usedTokens + targetTokens > maxInputTokens)
            throw new ContextWindowExceededException("current user message and generation continuation exceed model input budget");

        List<Message> sourcedContext = new ArrayList<>();
        int sourceTokenLimit = maxInputTokens - usedTokens - (target.role() == MessageRole.SYSTEM ? 0 : targetTokens);
        int sourceTokens = 0;
        int historyFloorIndex = 0;
        for (ContextSource source : contextSources) {
            boolean admittedAny = false;
            int admittedTokens = 0;
            for (Message message : source.load(conversation, generation)) {
                int tokens = tokenEstimator.estimateTokens(message);
                if (sourceTokens + tokens <= sourceTokenLimit) {
                    sourcedContext.add(message);
                    sourceTokens += tokens;
                    admittedTokens += tokens;
                    admittedAny = true;
                }
            }
            if (admittedTokens > 0) telemetry.contextTokens(source.kind().name().toLowerCase(), admittedTokens);
            if (admittedAny) {
                historyFloorIndex = Math.max(historyFloorIndex,
                        source.replacesHistoryThrough(conversation, generation)
                                .map(messageId -> historyFloorAfter(history, messageId, targetIndex)).orElse(0));
            }
        }
        usedTokens += sourceTokens;

        List<Message> recent = new ArrayList<>();
        int historyTokens = 0;
        for (int index = targetIndex; index >= historyFloorIndex; index--) {
            Message message = history.get(index);
            if (message.role() == MessageRole.SYSTEM) continue;
            int tokens = tokenEstimator.estimateTokens(message);
            if (usedTokens + tokens > maxInputTokens) break;
            recent.add(message);
            usedTokens += tokens;
            historyTokens += tokens;
        }
        Collections.reverse(recent);

        if (systemTokens > 0) telemetry.contextTokens("system", systemTokens);
        if (historyTokens > 0) telemetry.contextTokens("history", historyTokens);
        if (continuationTokens > 0) telemetry.contextTokens("generation_continuation", continuationTokens);

        List<Message> assembled = new ArrayList<>(
                systemMessages.size() + sourcedContext.size() + recent.size() + continuation.size());
        assembled.addAll(systemMessages);
        assembled.addAll(sourcedContext);
        assembled.addAll(recent);
        assembled.addAll(continuation);
        return List.copyOf(assembled);
    }

    private int historyFloorAfter(List<Message> messages, java.util.UUID messageId, int targetIndex) {
        for (int index = 0; index < messages.size(); index++)
            if (messages.get(index).id().equals(messageId)) return Math.min(index + 1, targetIndex);
        return 0;
    }

    private int indexOf(List<Message> messages, java.util.UUID messageId) {
        for (int index = 0; index < messages.size(); index++)
            if (messages.get(index).id().equals(messageId)) return index;
        throw new IllegalStateException("generation references missing user message: " + messageId);
    }

    private int tokenCount(List<Message> messages) {
        return messages.stream().mapToInt(tokenEstimator::estimateTokens).sum();
    }
}
