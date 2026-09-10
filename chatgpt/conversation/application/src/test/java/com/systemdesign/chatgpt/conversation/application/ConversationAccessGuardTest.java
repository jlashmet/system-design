package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ConversationMetadataStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationAccessGuardTest {
    private static final Instant NOW = Instant.parse("2026-09-10T23:30:00Z");

    @Test
    void permitsOwnerAndHidesResourceFromOtherSubjects() {
        UUID conversationId = UUID.randomUUID();
        ConversationMetadataStore store = id -> id.equals(conversationId)
                ? Optional.of(new ConversationMetadataStore.Metadata(conversationId, "user-1", NOW))
                : Optional.empty();
        ConversationAccessGuard guard = new ConversationAccessGuard(store);

        assertThatCode(() -> guard.requireOwner(conversationId, "user-1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireOwner(conversationId, "user-2"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("conversation not found");
    }
}
