package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.application.CancelGenerationHandler;
import com.systemdesign.chatgpt.conversation.application.CreateConversationHandler;
import com.systemdesign.chatgpt.conversation.application.GetConversationHandler;
import com.systemdesign.chatgpt.conversation.application.GetGenerationHandler;
import com.systemdesign.chatgpt.conversation.application.ProcessGenerationHandler;
import com.systemdesign.chatgpt.conversation.application.SendMessageHandler;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.GenerationEventBus;
import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import com.systemdesign.chatgpt.conversation.infrastructure.output.DeterministicModelGateway;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryConversationRepository;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryGenerationEventBus;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryInferenceJobQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

@Configuration
public class ConversationConfiguration {
    @Bean
    InMemoryConversationRepository conversationStore() {
        return new InMemoryConversationRepository();
    }

    @Bean
    ConversationRepository conversationRepository(InMemoryConversationRepository store) {
        return store;
    }

    @Bean
    TurnRepository turnRepository(InMemoryConversationRepository store) {
        return store;
    }

    @Bean
    InferenceJobQueue inferenceJobQueue(
            @Value("${chatgpt.inference.queue-capacity:1024}") int capacity) {
        return new InMemoryInferenceJobQueue(capacity);
    }

    @Bean
    GenerationEventBus generationEventBus() {
        return new InMemoryGenerationEventBus();
    }

    @Bean
    ModelGateway modelGateway() {
        return new DeterministicModelGateway();
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
            Supplier<UUID> idGenerator,
            Clock clock) {
        return new SendMessageHandler(repository, turnRepository, inferenceJobQueue, idGenerator, clock);
    }

    @Bean
    ProcessGenerationHandler processGenerationHandler(
            ConversationRepository repository,
            TurnRepository turnRepository,
            ModelGateway modelGateway,
            GenerationEventBus generationEventBus,
            Supplier<UUID> idGenerator,
            Clock clock) {
        return new ProcessGenerationHandler(
                repository, turnRepository, modelGateway, generationEventBus, idGenerator, clock);
    }
}
