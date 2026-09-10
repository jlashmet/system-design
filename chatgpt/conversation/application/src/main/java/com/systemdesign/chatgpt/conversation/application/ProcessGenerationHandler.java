package com.systemdesign.chatgpt.conversation.application;

import com.systemdesign.chatgpt.conversation.domain.ContextAssembler;
import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.ConversationTelemetry;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;
import com.systemdesign.chatgpt.conversation.domain.RunningMessageStore;
import com.systemdesign.chatgpt.conversation.domain.ToolCall;
import com.systemdesign.chatgpt.conversation.domain.ToolDefinition;
import com.systemdesign.chatgpt.conversation.domain.ToolResult;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class ProcessGenerationHandler {
    private final ConversationRepository conversationRepository;
    private final TurnRepository turnRepository;
    private final RunningMessageStore runningMessageStore;
    private final ContextAssembler contextAssembler;
    private final ModelGateway modelGateway;
    private final GenerationEventBus eventBus;
    private final ConversationSummaryRefresher summaryRefresher;
    private final ToolExecutor toolExecutor;
    private final ConversationTelemetry telemetry;
    private final int maxToolRounds;
    private final Supplier<UUID> idGenerator;
    private final Clock clock;

    public ProcessGenerationHandler(ConversationRepository conversationRepository, TurnRepository turnRepository,
            RunningMessageStore runningMessageStore, ContextAssembler contextAssembler, ModelGateway modelGateway,
            GenerationEventBus eventBus, ConversationSummaryRefresher summaryRefresher, ToolExecutor toolExecutor,
            int maxToolRounds, Supplier<UUID> idGenerator, Clock clock) {
        this(conversationRepository, turnRepository, runningMessageStore, contextAssembler, modelGateway, eventBus,
                summaryRefresher, toolExecutor, ConversationTelemetry.noop(), maxToolRounds, idGenerator, clock);
    }

    public ProcessGenerationHandler(ConversationRepository conversationRepository, TurnRepository turnRepository,
            RunningMessageStore runningMessageStore, ContextAssembler contextAssembler, ModelGateway modelGateway,
            GenerationEventBus eventBus, ConversationSummaryRefresher summaryRefresher, ToolExecutor toolExecutor,
            ConversationTelemetry telemetry, int maxToolRounds, Supplier<UUID> idGenerator, Clock clock) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository, "conversationRepository");
        this.turnRepository = Objects.requireNonNull(turnRepository, "turnRepository");
        this.runningMessageStore = Objects.requireNonNull(runningMessageStore, "runningMessageStore");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.summaryRefresher = Objects.requireNonNull(summaryRefresher, "summaryRefresher");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        if (maxToolRounds < 0) throw new IllegalArgumentException("maxToolRounds must be >= 0");
        this.maxToolRounds = maxToolRounds;
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void handle(UUID generationId) {
        Instant claimedAt = Instant.now(clock);
        Generation generation = turnRepository.claim(generationId, claimedAt).orElse(null);
        if (generation == null) {
            if (turnRepository.findGenerationById(generationId).isEmpty()) throw new NoSuchElementException("generation not found: " + generationId);
            return;
        }
        telemetry.generationStarted(Duration.between(generation.createdAt(), claimedAt));
        AtomicBoolean firstToken = new AtomicBoolean();
        Conversation conversation = loadConversation(generation);
        try {
            List<ToolDefinition> tools = generation.requiredCapabilities().contains(ModelCapability.TOOL_CALLING)
                    ? toolExecutor.definitionsFor(conversation.userId(), conversation.id()) : List.of();
            for (int round = 0; ; round++) {
                List<Message> context = contextAssembler.assemble(conversation, generation);
                Instant modelStarted = Instant.now(clock);
                ModelGateway.TurnResult turn;
                try {
                    turn = modelGateway.streamTurn(context, generation.requiredCapabilities(), tools, delta -> {
                        if (firstToken.compareAndSet(false, true)) telemetry.firstToken(Duration.between(claimedAt, Instant.now(clock)));
                        publishDeltaUnlessCancelled(generation.id(), delta);
                    });
                    telemetry.modelRound(Duration.between(modelStarted, Instant.now(clock)),
                            turn instanceof ModelGateway.FinalResponse ? "final" : "tool_requests");
                } catch (RuntimeException exception) {
                    telemetry.modelRound(Duration.between(modelStarted, Instant.now(clock)), "failed");
                    throw exception;
                }
                if (turn instanceof ModelGateway.FinalResponse finalResponse) {
                    completeGeneration(conversation, generation, finalResponse.completion());
                    GenerationStatus persisted = turnRepository.findGenerationById(generation.id()).orElseThrow().status();
                    telemetry.generationFinished(Duration.between(generation.createdAt(), Instant.now(clock)),
                            persisted.name().toLowerCase());
                    return;
                }
                ModelGateway.ToolRequests requests = (ModelGateway.ToolRequests) turn;
                if (tools.isEmpty()) throw new IllegalStateException("model requested tools when no authorized tools were available");
                if (round >= maxToolRounds) throw new ToolRoundLimitExceededException(maxToolRounds);
                List<Message> transcript = executeToolRound(conversation, generation.id(), requests.calls());
                if (!runningMessageStore.append(generation.id(), transcript)) {
                    telemetry.generationFinished(Duration.between(generation.createdAt(), Instant.now(clock)), "cancelled");
                    return;
                }
                conversation = loadConversation(generation);
            }
        } catch (GenerationCancelledException ignored) {
            telemetry.generationFinished(Duration.between(generation.createdAt(), Instant.now(clock)), "cancelled");
        } catch (RuntimeException exception) {
            turnRepository.fail(generation.failed(Instant.now(clock)));
            if (!isCancelled(generation.id())) eventBus.publish(generation.id(), GenerationEventBus.Event.failed(exception.getMessage()));
            telemetry.generationFinished(Duration.between(generation.createdAt(), Instant.now(clock)),
                    isCancelled(generation.id()) ? "cancelled" : "failed");
            throw exception;
        }
    }

    private Conversation loadConversation(Generation generation) {
        return conversationRepository.findById(generation.conversationId())
                .orElseThrow(() -> new NoSuchElementException("conversation not found: " + generation.conversationId()));
    }
    private List<Message> executeToolRound(Conversation conversation, UUID generationId, List<ToolCall> calls) {
        Instant base = nextMessageTime(conversation); List<Message> messages = new ArrayList<>(calls.size() + 1);
        messages.add(new Message(idGenerator.get(), MessageRole.ASSISTANT, formatToolRequests(calls), base)); int index = 1;
        for (ToolCall call : calls) {
            ToolResult result = toolExecutor.execute(conversation.userId(), conversation.id(), generationId, call);
            messages.add(new Message(idGenerator.get(), MessageRole.TOOL, formatToolResult(call, result), base.plusNanos(index++)));
        }
        return List.copyOf(messages);
    }
    private String formatToolRequests(List<ToolCall> calls) { return "Tool requests:\n" + calls.stream().map(call -> call.id() + ":" + call.name()).collect(java.util.stream.Collectors.joining("\n")); }
    private String formatToolResult(ToolCall call, ToolResult result) { return "tool_call_id=" + call.id() + "\ntool=" + call.name() + "\nstatus=" + result.status().name().toLowerCase() + "\nresult=" + result.content(); }
    private void completeGeneration(Conversation conversation, Generation generation, ModelGateway.Completion completion) {
        if (isCancelled(generation.id())) return;
        Instant completedAt = nextMessageTime(conversation); Message assistantMessage = new Message(idGenerator.get(), MessageRole.ASSISTANT, completion.content(), completedAt);
        conversation.append(assistantMessage); turnRepository.complete(conversation, generation.completed(assistantMessage.id(), completedAt));
        Generation finalState = turnRepository.findGenerationById(generation.id()).orElseThrow();
        if (finalState.status() == GenerationStatus.COMPLETED) { eventBus.publish(generation.id(), GenerationEventBus.Event.completed()); refreshSummaryBestEffort(conversation); }
    }
    private Instant nextMessageTime(Conversation conversation) {
        Instant now = Instant.now(clock); List<Message> messages = conversation.messages(); if (messages.isEmpty()) return now;
        Instant last = messages.getLast().createdAt(); return now.isAfter(last) ? now : last.plusNanos(1);
    }
    private void refreshSummaryBestEffort(Conversation conversation) { try { summaryRefresher.refreshIfNeeded(conversation); } catch (RuntimeException ignored) { } }
    private void publishDeltaUnlessCancelled(UUID generationId, String delta) { if (isCancelled(generationId)) throw new GenerationCancelledException(); eventBus.publish(generationId, GenerationEventBus.Event.delta(delta)); }
    private boolean isCancelled(UUID generationId) { return turnRepository.findGenerationById(generationId).map(Generation::status).filter(status -> status == GenerationStatus.CANCELLED).isPresent(); }
    private static final class GenerationCancelledException extends RuntimeException { }
}
