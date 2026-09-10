package com.systemdesign.chatgpt.conversation.infrastructure.input;

import com.systemdesign.chatgpt.conversation.api.ConversationResponse;
import com.systemdesign.chatgpt.conversation.api.CreateConversationRequest;
import com.systemdesign.chatgpt.conversation.api.CreateConversationResponse;
import com.systemdesign.chatgpt.conversation.api.GenerationResponse;
import com.systemdesign.chatgpt.conversation.api.MessageResponse;
import com.systemdesign.chatgpt.conversation.api.SendMessageRequest;
import com.systemdesign.chatgpt.conversation.application.CancelGenerationHandler;
import com.systemdesign.chatgpt.conversation.application.CreateConversationCommand;
import com.systemdesign.chatgpt.conversation.application.CreateConversationHandler;
import com.systemdesign.chatgpt.conversation.application.GetConversationHandler;
import com.systemdesign.chatgpt.conversation.application.GetGenerationHandler;
import com.systemdesign.chatgpt.conversation.application.SendMessageCommand;
import com.systemdesign.chatgpt.conversation.application.SendMessageHandler;
import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ReplayableGenerationEventBus;
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

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/conversations")
public final class ConversationController {
    private final CreateConversationHandler createConversationHandler;
    private final GetConversationHandler getConversationHandler;
    private final GetGenerationHandler getGenerationHandler;
    private final CancelGenerationHandler cancelGenerationHandler;
    private final SendMessageHandler sendMessageHandler;
    private final ReplayableGenerationEventBus generationEventBus;

    public ConversationController(
            CreateConversationHandler createConversationHandler,
            GetConversationHandler getConversationHandler,
            GetGenerationHandler getGenerationHandler,
            CancelGenerationHandler cancelGenerationHandler,
            SendMessageHandler sendMessageHandler,
            ReplayableGenerationEventBus generationEventBus) {
        this.createConversationHandler = createConversationHandler;
        this.getConversationHandler = getConversationHandler;
        this.getGenerationHandler = getGenerationHandler;
        this.cancelGenerationHandler = cancelGenerationHandler;
        this.sendMessageHandler = sendMessageHandler;
        this.generationEventBus = generationEventBus;
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
        return toResponse(sendMessageHandler.handle(new SendMessageCommand(
                conversationId,
                idempotencyKey,
                request.content(),
                parseCapabilities(request.requiredCapabilities()))).generation());
    }

    @GetMapping("/{conversationId}/generations/{generationId}")
    public GenerationResponse getGeneration(
            @PathVariable UUID conversationId,
            @PathVariable UUID generationId) {
        return toResponse(getGenerationHandler.handle(conversationId, generationId));
    }

    @PostMapping("/{conversationId}/generations/{generationId}/cancel")
    public GenerationResponse cancelGeneration(
            @PathVariable UUID conversationId,
            @PathVariable UUID generationId) {
        return toResponse(cancelGenerationHandler.handle(conversationId, generationId));
    }

    @GetMapping(value = "/{conversationId}/generations/{generationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGeneration(
            @PathVariable UUID conversationId,
            @PathVariable UUID generationId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        Generation current = getGenerationHandler.handle(conversationId, generationId);
        long afterSequence = parseLastEventId(lastEventId);
        SseEmitter emitter = new SseEmitter(30_000L);
        AtomicReference<GenerationEventBus.Subscription> subscriptionRef = new AtomicReference<>();
        AtomicBoolean terminalDelivered = new AtomicBoolean(false);

        try {
            emitter.send(SseEmitter.event().name("generation").data(toResponse(current)));
        } catch (Exception exception) {
            emitter.completeWithError(exception);
            return emitter;
        }

        GenerationEventBus.Subscription subscription = generationEventBus.subscribe(
                generationId,
                afterSequence,
                recorded -> {
                    try {
                        GenerationEventBus.Event event = recorded.event();
                        emitter.send(SseEmitter.event()
                                .id(Long.toString(recorded.sequence()))
                                .name(event.type().name().toLowerCase())
                                .data(event.data()));
                        if (isTerminal(event.type())) {
                            terminalDelivered.set(true);
                            close(subscriptionRef);
                            emitter.complete();
                        }
                    } catch (Exception exception) {
                        close(subscriptionRef);
                        emitter.completeWithError(exception);
                    }
                });
        subscriptionRef.set(subscription);

        emitter.onCompletion(() -> close(subscriptionRef));
        emitter.onTimeout(() -> close(subscriptionRef));
        emitter.onError(ignored -> close(subscriptionRef));

        if (terminalDelivered.get()) {
            close(subscriptionRef);
        } else if (isTerminal(current.status())) {
            close(subscriptionRef);
            emitter.complete();
        }
        return emitter;
    }

    private long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0L;
        }
        long value = Long.parseLong(lastEventId);
        if (value < 0) {
            throw new IllegalArgumentException("Last-Event-ID must be >= 0");
        }
        return value;
    }

    private Set<ModelCapability> parseCapabilities(java.util.List<String> values) {
        return values.stream()
                .map(value -> value.trim().replace('-', '_').toUpperCase(Locale.ROOT))
                .map(ModelCapability::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean isTerminal(GenerationEventBus.Type type) {
        return type == GenerationEventBus.Type.COMPLETED
                || type == GenerationEventBus.Type.FAILED
                || type == GenerationEventBus.Type.CANCELLED;
    }

    private boolean isTerminal(GenerationStatus status) {
        return status == GenerationStatus.COMPLETED
                || status == GenerationStatus.FAILED
                || status == GenerationStatus.CANCELLED;
    }

    private void close(AtomicReference<GenerationEventBus.Subscription> subscriptionRef) {
        GenerationEventBus.Subscription subscription = subscriptionRef.getAndSet(null);
        if (subscription != null) {
            subscription.close();
        }
    }

    private ConversationResponse toResponse(Conversation conversation) {
        return new ConversationResponse(conversation.id(), conversation.userId(), conversation.createdAt(),
                conversation.messages().stream().map(this::toResponse).toList());
    }

    private MessageResponse toResponse(Message message) {
        return new MessageResponse(message.id(), message.role().name().toLowerCase(), message.content(), message.createdAt());
    }

    private GenerationResponse toResponse(Generation generation) {
        return new GenerationResponse(
                generation.id(),
                generation.status().name().toLowerCase(),
                generation.requiredCapabilities().stream().map(capability -> capability.name().toLowerCase()).sorted().toList(),
                generation.userMessageId(),
                generation.assistantMessageId(),
                generation.createdAt(),
                generation.updatedAt());
    }
}
