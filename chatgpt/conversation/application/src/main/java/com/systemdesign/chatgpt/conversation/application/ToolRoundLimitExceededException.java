package com.systemdesign.chatgpt.conversation.application;

public final class ToolRoundLimitExceededException extends RuntimeException {
    public ToolRoundLimitExceededException(int maxToolRounds) {
        super("model exceeded maximum tool rounds: " + maxToolRounds);
    }
}
