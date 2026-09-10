package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.TokenEstimator;

public final class HeuristicTokenEstimator implements TokenEstimator {
    @Override
    public int estimateTokens(Message message) {
        int contentTokens = Math.max(1, (message.content().length() + 3) / 4);
        return contentTokens + 4;
    }
}
