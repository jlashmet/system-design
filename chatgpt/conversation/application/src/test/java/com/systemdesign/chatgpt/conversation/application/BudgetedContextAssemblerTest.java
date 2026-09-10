package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ContextSource;
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
        case "summary", "retrieval", "memory" -> 2;
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

    @Test
    void higherPrioritySourcesWinBudgetBeforeLowerPrioritySourcesAndHistory() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = Conversation.start(conversationId, "user-1", NOW);
        Message recentUser = message(MessageRole.USER, "recent-user", 1);
        Message target = message(MessageRole.USER, "target", 2);
        conversation.append(recentUser);
        conversation.append(target);
        Generation generation = Generation.pending(
                UUID.randomUUID(), conversationId, "request-1", "target", target.id(), target.createdAt());

        ContextSource memory = source(ContextSource.Kind.LONG_TERM_MEMORY, 30, "memory");
        ContextSource retrieval = source(ContextSource.Kind.RETRIEVAL, 20, "retrieval");
        ContextSource summary = source(ContextSource.Kind.SUMMARY, 10, "summary");
        BudgetedContextAssembler assembler = new BudgetedContextAssembler(
                estimator,
                9,
                List.of(memory, retrieval, summary));

        List<Message> context = assembler.assemble(conversation, generation);

        assertThat(context)
                .extracting(Message::content)
                .containsExactly("summary", "retrieval", "memory", "target");
    }

    private ContextSource source(ContextSource.Kind kind, int priority, String content) {
        Message sourceMessage = message(MessageRole.SYSTEM, content, 0);
        return new ContextSource() {
            @Override
            public Kind kind() {
                return kind;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public List<Message> load(Conversation conversation, Generation generation) {
                return List.of(sourceMessage);
            }
        };
    }

    private Message message(MessageRole role, String content, long seconds) {
        return new Message(UUID.randomUUID(), role, content, NOW.plusSeconds(seconds));
    }
}
