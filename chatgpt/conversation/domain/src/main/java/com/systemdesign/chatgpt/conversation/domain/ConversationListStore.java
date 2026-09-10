package com.systemdesign.chatgpt.conversation.domain;

import java.util.List;

public interface ConversationListStore {
    Page list(String subjectId, int limit, String cursor);

    record Page(List<ConversationMetadataStore.Metadata> conversations, String nextCursor) {
        public Page {
            conversations = List.copyOf(conversations);
        }
    }
}
