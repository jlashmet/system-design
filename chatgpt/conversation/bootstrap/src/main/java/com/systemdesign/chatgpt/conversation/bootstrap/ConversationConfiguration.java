package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.application.BudgetedContextAssembler;
import com.systemdesign.chatgpt.conversation.application.CancelGenerationHandler;
import com.systemdesign.chatgpt.conversation.application.ConversationSummaryContextSource;
import com.systemdesign.chatgpt.conversation.application.ConversationSummaryRefresher;
import com.systemdesign.chatgpt.conversation.application.CreateConversationHandler;
import com.systemdesign.chatgpt.conversation.application.GetConversationHandler;
import com.systemdesign.chatgpt.conversation.application.GetGenerationHandler;
import com.systemdesign.chatgpt.conversation.application.LongTermMemoryContextSource;
import com.systemdesign.chatgpt.conversation.application.ProcessGenerationHandler;
import com.systemdesign.chatgpt.conversation.application.RetrievalContextSource;
import com.systemdesign.chatgpt.conversation.application.RoutingModelGateway;
import com.systemdesign.chatgpt.conversation.application.SendMessageHandler;
import com.systemdesign.chatgpt.conversation.application.ToolExecutor;
import com.systemdesign.chatgpt.conversation.domain.ContextAssembler;
import com.systemdesign.chatgpt.conversation.domain.ContextSource;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummarizer;
import com.systemdesign.chatgpt.conversation.domain.ConversationSummaryStore;
import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import com.systemdesign.chatgpt.conversation.domain.InferenceQuota;
import com.systemdesign.chatgpt.conversation.domain.LongTermMemoryStore;
import com.systemdesign.chatgpt.conversation.domain.ModelEndpoint;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;
import com.systemdesign.chatgpt.conversation.domain.RetrievalContextStore;
import com.systemdesign.chatgpt.conversation.domain.RunningMessageStore;
import com.systemdesign.chatgpt.conversation.domain.TokenEstimator;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import com.systemdesign.chatgpt.conversation.infrastructure.output.DeterministicModelGateway;
import com.systemdesign.chatgpt.conversation.infrastructure.output.ExtractiveConversationSummarizer;
import com.systemdesign.chatgpt.conversation.infrastructure.output.HeuristicTokenEstimator;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryConversationSummaryStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryFixedWindowInferenceQuota;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryLongTermMemoryStore;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryRetrievalContextStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Configuration
public class ConversationConfiguration {
    @Bean
    InferenceQuota inferenceQuota(
            @Value("${chatgpt.inference.requests-per-minute:60}") int maxRequests) {
        return new InMemoryFixedWindowInferenceQuota(maxRequests, Duration.ofMinutes(1));
    }

    @Bean
    TokenEstimator tokenEstimator() {
        return new HeuristicTokenEstimator();
    }

    @Bean
    ConversationSummaryStore conversationSummaryStore() {
        return new InMemoryConversationSummaryStore();
    }

    @Bean
    RetrievalContextStore retrievalContextStore() {
        return new InMemoryRetrievalContextStore();
    }

    @Bean
    LongTermMemoryStore longTermMemoryStore() {
        return new InMemoryLongTermMemoryStore();
    }

    @Bean
    ConversationSummarizer conversationSummarizer(
            @Value("${chatgpt.context.summary-max-characters:2000}") int maxCharacters) {
        return new ExtractiveConversationSummarizer(maxCharacters);
    }

    @Bean
    ConversationSummaryRefresher conversationSummaryRefresher(
            ConversationSummaryStore store,
            ConversationSummarizer summarizer,
            @Value("${chatgpt.context.summary-refresh-messages:6}") int minUnsummarizedMessages,
            Supplier<UUID> idGenerator,
            Clock clock) {
        return new ConversationSummaryRefresher(
                store, summarizer, minUnsummarizedMessages, idGenerator, clock);
    }

    @Bean
    ContextSource conversationSummaryContextSource(
            ConversationSummaryStore store,
            @Value("${chatgpt.context.summary-priority:10}") int priority) {
        return new ConversationSummaryContextSource(store, priority);
    }

    @Bean
    ContextSource retrievalContextSource(
            RetrievalContextStore store,
            @Value("${chatgpt.context.retrieval-priority:20}") int priority,
            @Value("${chatgpt.context.retrieval-limit:5}") int limit) {
        return new RetrievalContextSource(store, priority, limit);
    }

    @Bean
    ContextSource longTermMemoryContextSource(
            LongTermMemoryStore store,
            @Value("${chatgpt.context.memory-priority:30}") int priority,
            @Value("${chatgpt.context.memory-limit:10}") int limit) {
        return new LongTermMemoryContextSource(store, priority, limit);
    }

    @Bean
    ContextAssembler contextAssembler(
            TokenEstimator tokenEstimator,
            List<ContextSource> contextSources,
            @Value("${chatgpt.context.max-input-tokens:8192}") int maxInputTokens) {
        return new BudgetedContextAssembler(tokenEstimator, maxInputTokens, contextSources);
    }

    @Bean
    ModelEndpoint deterministicModelEndpoint() {
        return new DeterministicModelGateway();
    }

    @Bean
    ModelGateway modelGateway(List<ModelEndpoint> endpoints) {
        return new RoutingModelGateway(endpoints);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Supplier<UUID> idGenerator() {
        return UUID::randomUUID;
    }

    @Bean
    CreateConversationHandler createConversationHandler(
            ConversationRepository repository,
            Supplier<UUID> idGenerator,
            Clock clock) {
        return new CreateConversationHandler(repository, idGenerator, clock);
    }

    @Bean
    GetConversationHandler getConversationHandler(ConversationRepository repository) {
        return new GetConversationHandler(repository);
    }

    @Bean
    GetGenerationHandler getGenerationHandler(TurnRepository turnRepository) {
        return new GetGenerationHandler(turnRepository);
    }

    @Bean
    CancelGenerationHandler cancelGenerationHandler(
            TurnRepository turnRepository,
            GenerationEventBus generationEventBus,
            Clock clock) {
        return new CancelGenerationHandler(turnRepository, generationEventBus, clock);
    }

    @Bean
    SendMessageHandler sendMessageHandler(
            ConversationRepository repository,
            TurnRepository turnRepository,
            InferenceJobQueue inferenceJobQueue,
            InferenceQuota inferenceQuota,
            Supplier<UUID> idGenerator,
            Clock clock) {
        return new SendMessageHandler(
                repository, turnRepository, inferenceJobQueue, inferenceQuota, idGenerator, clock);
    }

    @Bean
    ProcessGenerationHandler processGenerationHandler(
            ConversationRepository repository,
            TurnRepository turnRepository,
            RunningMessageStore runningMessageStore,
            ContextAssembler contextAssembler,
            ModelGateway modelGateway,
            GenerationEventBus generationEventBus,
            ConversationSummaryRefresher summaryRefresher,
            ToolExecutor toolExecutor,
            @Value("${chatgpt.tools.max-rounds:4}") int maxToolRounds,
            Supplier<UUID> idGenerator,
            Clock clock) {
        return new ProcessGenerationHandler(
                repository,
                turnRepository,
                runningMessageStore,
                contextAssembler,
                modelGateway,
                generationEventBus,
                summaryRefresher,
                toolExecutor,
                maxToolRounds,
                idGenerator,
                clock);
    }
}
