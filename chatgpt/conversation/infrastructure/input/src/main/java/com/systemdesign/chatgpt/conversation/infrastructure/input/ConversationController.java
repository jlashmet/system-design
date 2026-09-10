package com.systemdesign.chatgpt.conversation.infrastructure.input;

import com.systemdesign.chatgpt.conversation.api.ConversationResponse;
import com.systemdesign.chatgpt.conversation.api.CreateConversationRequest;
import com.systemdesign.chatgpt.conversation.api.CreateConversationResponse;
import com.systemdesign.chatgpt.conversation.api.GenerationResponse;
import com.systemdesign.chatgpt.conversation.api.MessageResponse;
import com.systemdesign.chatgpt.conversation.api.SendMessageRequest;
import com.systemdesign.chatgpt.conversation.application.CreateConversationCommand;
import com.systemdesign.chatgpt.conversation.application.CreateConversationHandler;
import com.systemdesign.chatgpt.conversation.application.GetConversationHandler;
import com.systemdesign.chatgpt.conversation.application.GetGenerationHandler;
import com.systemdesign.chatgpt.conversation.application.SendMessageCommand;
import com.systemdesign.chatgpt.conversation.application.SendMessageHandler;
import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.Message;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/v1/conversations")
public final class ConversationController {
    private final CreateConversationHandler createConversationHandler;
    private final GetConversationHandler getConversationHandler;
    private final GetGenerationHandler getGenerationHandler;
    private final SendMessageHandler sendMessageHandler;

    public ConversationController(
            CreateConversationHandler createConversationHandler,
            GetConversationHandler getConversationHandler,
            GetGenerationHandler getGenerationHandler,
            SendMessageHandler sendMessageHandler) {
        this.createConversationHandler = createConversationHandler;
        this.getConversationHandler = getConversationHandler;
        this.getGenerationHandler = getGenerationHandler;
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
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GenerationResponse sendMessage(
            @PathVariable UUID conversationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SendMessageRequest request) {
        return toResponse(sendMessageHandler.handle(
                new SendMessageCommand(conversationId, idempotencyKey, request.content())).generation());
    }

    @GetMapping("/{conversationId}/generations/{generationId}")
    public GenerationResponse getGeneration(
            @PathVariable UUID conversationId,
            @PathVariable UUID generationId) {
        return toResponse(getGenerationHandler.handle(conversationId, generationId));
    }

    @GetMapping(
            value = "/{conversationId}/generations/{generationId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGeneration(
            @PathVariable UUID conversationId,
            @PathVariable UUID generationId) {
        SseEmitter emitter = new SseEmitter(30_000L);
        Thread.ofVirtual().name("generation-sse-" + generationId).start(() -> {
            try {
                Generation previous = null;
                while (true) {
                    Generation current = getGenerationHandler.handle(conversationId, generationId);
                    if (!current.equals(previous)) {
                        emitter.send(SseEmitter.event().name("generation").data(toResponse(current)));
                        previous = current;
                    }
                    if (current.status() == GenerationStatus.COMPLETED
                            || current.status() == GenerationStatus.FAILED) {
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(25L);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                emitter.completeWithError(exception);
            } catch (Exception exception) {
                emitter.completeWithError(exception);
            }
        });
        return emitter;
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

    private GenerationResponse toResponse(Generation generation) {
        return new GenerationResponse(
                generation.id(),
                generation.status().name().toLowerCase(),
                generation.userMessageId(),
                generation.assistantMessageId(),
                generation.createdAt(),
                generation.updatedAt());
    }
}
