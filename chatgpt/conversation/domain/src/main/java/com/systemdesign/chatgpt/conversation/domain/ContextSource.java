package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;

public interface ContextSource {
    Kind kind();

    int priority();

    List<Message> load(Conversation conversation, Generation generation);

    enum Kind {
        SUMMARY,
        RETRIEVAL,
        LONG_TERM_MEMORY
    }
}
