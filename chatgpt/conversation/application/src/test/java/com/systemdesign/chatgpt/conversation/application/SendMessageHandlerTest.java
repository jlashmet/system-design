package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class SendMessageHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    private final UUID conversationId = UUID.randomUUID();
    private SendMessageHandler handler;
    private SendMessageResult result;

    @Test
    void appendsUserAndAssistantMessages() {
        given();
        whenSendMessage("hello");
        thenExpect("hello", "assistant: hello");
    }

    private void given() {
        FakeConversationRepository repository = new FakeConversationRepository();
        repository.save(Conversation.start(conversationId, "user-1", NOW));
        ModelGateway modelGateway = messages -> new ModelGateway.Completion(
                "test-model",
                "assistant: " + messages.getLast().content());
        handler = new SendMessageHandler(
                repository,
                modelGateway,
                UUID::randomUUID,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
    }

    private void whenSendMessage(String content) {
        result = handler.handle(new SendMessageCommand(conversationId, content));
    }

    private void thenExpect(String userContent, String assistantContent) {
        assertThat(result.conversation().messages())
                .extracting(Message::role, Message::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MessageRole.USER, userContent),
                        org.assertj.core.groups.Tuple.tuple(MessageRole.ASSISTANT, assistantContent));
    }

    private static final class FakeConversationRepository implements ConversationRepository {
        private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();

        @Override
        public Optional<Conversation> findById(UUID conversationId) {
            return Optional.ofNullable(conversations.get(conversationId));
        }

        @Override
        public void save(Conversation conversation) {
            conversations.put(conversation.id(), conversation);
        }
    }
}
