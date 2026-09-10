package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;

public interface ContextAssembler {
    List<Message> assemble(Conversation conversation, Generation generation);
}
