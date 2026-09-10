package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ConversationMessagePageStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetConversationMessagesHandlerTest {
    @Test
    void rejectsPageSizesOutsideConfiguredBoundBeforeReadingStorage() {
        ConversationMessagePageStore store = (conversationId, limit, cursor) ->
                new ConversationMessagePageStore.Page(List.of(), null);
        GetConversationMessagesHandler handler = new GetConversationMessagesHandler(store, 100);
        UUID conversationId = UUID.randomUUID();

        assertThatThrownBy(() -> handler.handle(conversationId, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.handle(conversationId, 101, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
