package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ConversationSummarizer;
import com.systemdesign.chatgpt.conversation.domain.Message;

import java.util.List;

public final class ExtractiveConversationSummarizer implements ConversationSummarizer {
    private final int maxCharacters;

    public ExtractiveConversationSummarizer(int maxCharacters) {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("maxCharacters must be >= 1");
        }
        this.maxCharacters = maxCharacters;
    }

    @Override
    public String summarize(String previousSummary, List<Message> newMessages) {
        StringBuilder builder = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            builder.append(previousSummary.trim()).append('\n');
        }
        for (Message message : newMessages) {
            builder.append(message.role().name().toLowerCase())
                    .append(": ")
                    .append(message.content().trim())
                    .append('\n');
        }
        String summary = builder.toString().trim();
        if (summary.length() <= maxCharacters) {
            return summary;
        }
        return summary.substring(summary.length() - maxCharacters);
    }
}
