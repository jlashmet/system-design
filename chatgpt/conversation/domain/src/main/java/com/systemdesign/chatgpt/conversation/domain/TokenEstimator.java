package com.systemdesign.chatgpt.conversation.domain;

public interface TokenEstimator {
    int estimateTokens(Message message);
}
