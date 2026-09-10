package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;

public interface ConversationSummarizer {
    String summarize(String previousSummary, List<Message> newMessages);
}
