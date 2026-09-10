package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ConversationListStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListConversationsHandlerTest {
    @Test
    void scopesQueryToSubjectAndEnforcesPageLimit() {
        RecordingStore store = new RecordingStore();
        ListConversationsHandler handler = new ListConversationsHandler(store, 100);

        ConversationListStore.Page page = handler.handle("user-1", 50, "cursor-1");

        assertThat(page.conversations()).isEmpty();
        assertThat(store.subjectId).isEqualTo("user-1");
        assertThat(store.limit).isEqualTo(50);
        assertThat(store.cursor).isEqualTo("cursor-1");
        assertThatThrownBy(() -> handler.handle("user-1", 101, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class RecordingStore implements ConversationListStore {
        private String subjectId;
        private int limit;
        private String cursor;
        @Override public Page list(String subjectId, int limit, String cursor) {
            this.subjectId = subjectId; this.limit = limit; this.cursor = cursor;
            return new Page(List.of(), null);
        }
    }
}
