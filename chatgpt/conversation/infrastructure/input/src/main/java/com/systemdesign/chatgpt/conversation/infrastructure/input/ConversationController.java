package com.systemdesign.chatgpt.conversation.infrastructure.input;

import com.systemdesign.chatgpt.conversation.api.ConversationResponse;
import com.systemdesign.chatgpt.conversation.api.CreateConversationRequest;
import com.systemdesign.chatgpt.conversation.api.CreateConversationResponse;
import com.systemdesign.chatgpt.conversation.api.MessageResponse;
import com.systemdesign.chatgpt.conversation.api.SendMessageRequest;
import com.systemdesign.chatgpt.conversation.application.CreateConversationCommand;
import com.systemdesign.chatgpt.conversation.application.CreateConversationHandler;
import com.systemdesign.chatgpt.conversation.application.GetConversationHandler;
import com.systemdesign.chatgpt.conversation.application.SendMessageCommand;
import com.systemdesign.chatgpt.conversation.application.SendMessageHandler;
import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Message;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/conversations")
public final class ConversationController {
    private final CreateConversationHandler createConversationHandler;
    private final GetConversationHandler getConversationHandler;
    private final SendMessageHandler sendMessageHandler;

    public ConversationController(
            CreateConversationHandler createConversationHandler,
            GetConversationHandler getConversationHandler,
            SendMessageHandler sendMessageHandler) {
        this.createConversationHandler = createConversationHandler;
        this.getConversationHandler = getConversationHandler;
        this.sendMessageHandler = sendMessageHandler;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateConversationResponse create(@RequestBody CreateConversationRequest request) {
        Conversation conversation = createConversationHandler.handle(new CreateConversationCommand(request.userId()));
        return new CreateConversationResponse(conversation.id());
    }

    @GetMapping("/{conversationId}")
    public ConversationResponse get(@PathVariable UUID conversationId) {
        return toResponse(getConversationHandler.handle(conversationId));
    }

    @PostMapping("/{conversationId}/messages")
    public ConversationResponse sendMessage(
            @PathVariable UUID conversationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SendMessageRequest request) {
        return toResponse(sendMessageHandler.handle(
                new SendMessageCommand(conversationId, idempotencyKey, request.content())).conversation());
    }

    private ConversationResponse toResponse(Conversation conversation) {
        return new ConversationResponse(
                conversation.id(),
                conversation.userId(),
                conversation.createdAt(),
                conversation.messages().stream().map(this::toResponse).toList());
    }

    private MessageResponse toResponse(Message message) {
        return new MessageResponse(
                message.id(),
                message.role().name().toLowerCase(),
                message.content(),
                message.createdAt());
    }
}
