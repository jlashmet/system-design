package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryConversationRepository implements ConversationRepository, TurnRepository {
    private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();
    private final Map<TurnKey, Generation> generations = new ConcurrentHashMap<>();
    private final Map<UUID, Generation> generationsById = new ConcurrentHashMap<>();

    @Override
    public Optional<Conversation> findById(UUID conversationId) {
        return Optional.ofNullable(conversations.get(conversationId)).map(this::copy);
    }

    @Override
    public void save(Conversation conversation) {
        conversations.put(conversation.id(), copy(conversation));
    }

    @Override
    public Optional<Generation> findGenerationById(UUID generationId) {
        return Optional.ofNullable(generationsById.get(generationId));
    }

    @Override
    public Optional<Generation> findByIdempotencyKey(UUID conversationId, String idempotencyKey) {
        return Optional.ofNullable(generations.get(new TurnKey(conversationId, idempotencyKey)));
    }

    @Override
    public synchronized BeginResult begin(Conversation conversation, Generation generation) {
        TurnKey key = new TurnKey(generation.conversationId(), generation.idempotencyKey());
        Generation existing = generations.get(key);
        if (existing != null) {
            return new BeginResult(existing, false);
        }

        conversations.put(conversation.id(), copy(conversation));
        putGeneration(generation);
        return new BeginResult(generation, true);
    }

    @Override
    public synchronized Optional<Generation> claim(UUID generationId, Instant startedAt) {
        Generation current = generationsById.get(generationId);
        if (current == null || current.status() == GenerationStatus.RUNNING || current.status() == GenerationStatus.COMPLETED) {
            return Optional.empty();
        }
        Generation running = current.running(startedAt);
        putGeneration(running);
        return Optional.of(running);
    }

    @Override
    public synchronized void complete(Conversation conversation, Generation generation) {
        conversations.put(conversation.id(), copy(conversation));
        putGeneration(generation);
    }

    @Override
    public synchronized void fail(Generation generation) {
        putGeneration(generation);
    }

    private void putGeneration(Generation generation) {
        generations.put(new TurnKey(generation.conversationId(), generation.idempotencyKey()), generation);
        generationsById.put(generation.id(), generation);
    }

    private Conversation copy(Conversation conversation) {
        return Conversation.rehydrate(
                conversation.id(),
                conversation.userId(),
                conversation.createdAt(),
                conversation.messages());
    }

    private record TurnKey(UUID conversationId, String idempotencyKey) {
    }
}
