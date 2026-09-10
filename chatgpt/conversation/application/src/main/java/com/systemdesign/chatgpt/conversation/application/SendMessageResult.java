package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Message;

public record SendMessageResult(Conversation conversation, Message userMessage, Message assistantMessage) {
}
