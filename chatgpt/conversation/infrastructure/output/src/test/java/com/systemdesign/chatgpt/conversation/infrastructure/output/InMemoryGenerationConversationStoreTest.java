package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryGenerationConversationStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-10T22:00:00Z");

    @Test
    void boundsHistoryAtTargetAndExcludesLaterTurns() {
        UUID conversationId = UUID.randomUUID();
        InMemoryConversationRepository repository = new InMemoryConversationRepository();
        Conversation conversation = Conversation.start(conversationId, "user-1", NOW);
        Message one = new Message(UUID.randomUUID(), MessageRole.USER, "one", NOW.plusSeconds(1));
        Message two = new Message(UUID.randomUUID(), MessageRole.ASSISTANT, "two", NOW.plusSeconds(2));
        Message target = new Message(UUID.randomUUID(), MessageRole.USER, "target", NOW.plusSeconds(3));
        Message later = new Message(UUID.randomUUID(), MessageRole.USER, "later", NOW.plusSeconds(4));
        conversation.append(one);
        conversation.append(two);
        conversation.append(target);
        conversation.append(later);
        repository.save(conversation);
        Generation generation = Generation.pending(
                UUID.randomUUID(), conversationId, "request-1", "target", target.id(), target.createdAt());

        Conversation bounded = new InMemoryGenerationConversationStore(repository).load(generation, 2).orElseThrow();

        assertThat(bounded.messages()).extracting(Message::content).containsExactly("two", "target");
    }
}
