package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetedContextAssemblerTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private final TokenEstimator estimator = message -> switch (message.content()) {
        case "system" -> 2;
        case "old-user", "old-assistant" -> 4;
        case "recent-user", "recent-assistant", "target", "later-user" -> 3;
        default -> 1;
    };

    @Test
    void trimsOldHistoryButKeepsSystemAndContiguousRecentSuffix() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = Conversation.start(conversationId, "user-1", NOW);
        Message system = message(MessageRole.SYSTEM, "system", 1);
        Message oldUser = message(MessageRole.USER, "old-user", 2);
        Message oldAssistant = message(MessageRole.ASSISTANT, "old-assistant", 3);
        Message recentUser = message(MessageRole.USER, "recent-user", 4);
        Message recentAssistant = message(MessageRole.ASSISTANT, "recent-assistant", 5);
        Message target = message(MessageRole.USER, "target", 6);
        List.of(system, oldUser, oldAssistant, recentUser, recentAssistant, target).forEach(conversation::append);
        Generation generation = Generation.pending(
                UUID.randomUUID(), conversationId, "request-1", "target", target.id(), target.createdAt());
        BudgetedContextAssembler assembler = new BudgetedContextAssembler(estimator, 11);

        List<Message> context = assembler.assemble(conversation, generation);

        assertThat(context)
                .extracting(Message::content)
                .containsExactly("system", "recent-user", "recent-assistant", "target");
    }

    @Test
    void excludesMessagesSubmittedAfterGenerationTarget() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = Conversation.start(conversationId, "user-1", NOW);
        Message target = message(MessageRole.USER, "target", 1);
        Message later = message(MessageRole.USER, "later-user", 2);
        conversation.append(target);
        conversation.append(later);
        Generation generation = Generation.pending(
                UUID.randomUUID(), conversationId, "request-1", "target", target.id(), target.createdAt());
        BudgetedContextAssembler assembler = new BudgetedContextAssembler(estimator, 20);

        List<Message> context = assembler.assemble(conversation, generation);

        assertThat(context).extracting(Message::content).containsExactly("target");
    }

    private Message message(MessageRole role, String content, long seconds) {
        return new Message(UUID.randomUUID(), role, content, NOW.plusSeconds(seconds));
    }
}
