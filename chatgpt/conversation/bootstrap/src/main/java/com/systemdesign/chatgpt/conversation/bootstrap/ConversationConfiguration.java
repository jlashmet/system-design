package com.systemdesign.chatgpt.conversation.bootstrap;

import com.systemdesign.chatgpt.conversation.application.CreateConversationHandler;
import com.systemdesign.chatgpt.conversation.application.GetConversationHandler;
import com.systemdesign.chatgpt.conversation.application.SendMessageHandler;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;
import com.systemdesign.chatgpt.conversation.infrastructure.output.DeterministicModelGateway;
import com.systemdesign.chatgpt.conversation.infrastructure.output.InMemoryConversationRepository;
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
    SendMessageHandler sendMessageHandler(
            ConversationRepository repository,
            TurnRepository turnRepository,
            ModelGateway modelGateway,
            Supplier<UUID> idGenerator,
            Clock clock) {
        return new SendMessageHandler(repository, turnRepository, modelGateway, idGenerator, clock);
    }
}
