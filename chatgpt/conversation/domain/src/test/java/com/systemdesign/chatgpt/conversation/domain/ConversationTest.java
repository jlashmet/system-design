package com.systemdesign.chatgpt.conversation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-10T12:00:00Z");

    private Conversation conversation;

    @Test
    void appendsMessagesInOrder() {
        given();
        whenMessagesAreAppended("hello", "hi there");
        thenExpect(MessageRole.USER, "hello", MessageRole.ASSISTANT, "hi there");
    }

    private void given() {
        conversation = Conversation.start(UUID.randomUUID(), "user-1", CREATED_AT);
    }

    private void whenMessagesAreAppended(String userContent, String assistantContent) {
        conversation.append(new Message(UUID.randomUUID(), MessageRole.USER, userContent, CREATED_AT.plusSeconds(1)));
        conversation.append(new Message(UUID.randomUUID(), MessageRole.ASSISTANT, assistantContent, CREATED_AT.plusSeconds(2)));
    }

    private void thenExpect(MessageRole firstRole, String firstContent, MessageRole secondRole, String secondContent) {
        assertThat(conversation.messages())
                .extracting(Message::role, Message::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(firstRole, firstContent),
                        org.assertj.core.groups.Tuple.tuple(secondRole, secondContent));
    }
}
