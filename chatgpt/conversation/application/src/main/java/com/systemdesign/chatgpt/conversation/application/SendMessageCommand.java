package com.systemdesign.chatgpt.conversation.application;

import java.util.UUID;

public record SendMessageCommand(UUID conversationId, String content) {
}
