package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationListStore;
import com.systemdesign.chatgpt.conversation.domain.ConversationMetadataStore;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import com.systemdesign.chatgpt.conversation.domain.Generation;
import com.systemdesign.chatgpt.conversation.domain.GenerationContinuationStore;
import com.systemdesign.chatgpt.conversation.domain.GenerationStatus;
import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.RunningMessageStore;
import com.systemdesign.chatgpt.conversation.domain.TurnRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryConversationRepository implements ConversationRepository, ConversationMetadataStore,
        ConversationListStore, TurnRepository, RunningMessageStore, GenerationContinuationStore {
    private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();
    private final Map<TurnKey, Generation> generations = new ConcurrentHashMap<>();
    private final Map<UUID, Generation> generationsById = new ConcurrentHashMap<>();
    private final Map<UUID, List<Message>> continuationByGeneration = new ConcurrentHashMap<>();

    @Override public Optional<Conversation> findById(UUID conversationId) {
        return Optional.ofNullable(conversations.get(conversationId)).map(this::copy);
    }
    @Override public Optional<Metadata> find(UUID conversationId) {
        Conversation conversation = conversations.get(conversationId);
        return conversation == null ? Optional.empty()
                : Optional.of(new Metadata(conversation.id(), conversation.userId(), conversation.createdAt()));
    }
    @Override public Page list(String subjectId, int limit, String cursor) {
        if (subjectId == null || subjectId.isBlank()) throw new IllegalArgumentException("subjectId must not be blank");
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        Cursor after = decodeCursor(cursor);
        List<Conversation> ordered = conversations.values().stream()
                .filter(conversation -> subjectId.equals(conversation.userId()))
                .sorted(Comparator.comparing(Conversation::createdAt).reversed()
                        .thenComparing(conversation -> conversation.id().toString(), Comparator.reverseOrder()))
                .filter(conversation -> after == null || before(conversation, after))
                .limit(limit + 1L)
                .toList();
        boolean hasMore = ordered.size() > limit;
        List<Metadata> page = ordered.stream().limit(limit)
                .map(conversation -> new Metadata(conversation.id(), conversation.userId(), conversation.createdAt()))
                .toList();
        String next = hasMore && !page.isEmpty() ? encodeCursor(page.getLast()) : null;
        return new Page(page, next);
    }
    @Override public void save(Conversation conversation) { conversations.put(conversation.id(), copy(conversation)); }
    @Override public Optional<Generation> findGenerationById(UUID generationId) { return Optional.ofNullable(generationsById.get(generationId)); }
    @Override public Optional<Generation> findByIdempotencyKey(UUID conversationId, String idempotencyKey) {
        return Optional.ofNullable(generations.get(new TurnKey(conversationId, idempotencyKey)));
    }

    @Override public synchronized BeginResult begin(Conversation conversation, Generation generation) {
        TurnKey key = new TurnKey(generation.conversationId(), generation.idempotencyKey());
        Generation existing = generations.get(key);
        if (existing != null) return new BeginResult(existing, false);
        conversations.put(conversation.id(), copy(conversation));
        putGeneration(generation);
        return new BeginResult(generation, true);
    }

    @Override public synchronized Optional<Generation> claim(UUID generationId, Instant startedAt) {
        return claim(generationId, startedAt, startedAt.plusSeconds(60));
    }
    @Override public synchronized Optional<Generation> claim(UUID generationId, Instant startedAt, Instant leaseUntil) {
        Generation current = generationsById.get(generationId);
        if (current == null || current.status() == GenerationStatus.COMPLETED || current.status() == GenerationStatus.CANCELLED) return Optional.empty();
        if (current.status() == GenerationStatus.RUNNING && !current.leaseExpiredAt(startedAt)) return Optional.empty();
        Generation running = current.running(startedAt, UUID.randomUUID(), leaseUntil);
        putGeneration(running); return Optional.of(running);
    }
    @Override public synchronized boolean renewClaim(UUID generationId, UUID claimToken, Instant renewedAt, Instant leaseUntil) {
        Generation current = generationsById.get(generationId);
        if (current == null || current.status() != GenerationStatus.RUNNING
                || !java.util.Objects.equals(current.claimToken(), claimToken)) return false;
        if (!leaseUntil.isAfter(renewedAt)) throw new IllegalArgumentException("leaseUntil must be after renewedAt");
        putGeneration(current.running(renewedAt, claimToken, leaseUntil)); return true;
    }
    @Override public synchronized Optional<Generation> cancel(UUID generationId, Instant cancelledAt) {
        Generation current = generationsById.get(generationId);
        if (current == null) return Optional.empty();
        if (current.status() == GenerationStatus.COMPLETED || current.status() == GenerationStatus.CANCELLED) return Optional.of(current);
        Generation cancelled = current.cancelled(cancelledAt); putGeneration(cancelled); return Optional.of(cancelled);
    }
    @Override public synchronized boolean append(UUID generationId, List<Message> messages) {
        Generation current = generationsById.get(generationId);
        return current != null && append(generationId, current.claimToken(), messages);
    }
    @Override public synchronized boolean append(UUID generationId, UUID claimToken, List<Message> messages) {
        Generation generation = generationsById.get(generationId);
        if (generation == null || generation.status() != GenerationStatus.RUNNING
                || !java.util.Objects.equals(generation.claimToken(), claimToken)) return false;
        Conversation conversation = conversations.get(generation.conversationId());
        if (conversation == null) throw new IllegalStateException("conversation not found for generation: " + generationId);
        Conversation updated = copy(conversation); messages.forEach(updated::append);
        conversations.put(updated.id(), updated);
        continuationByGeneration.put(generationId, concat(continuationByGeneration.getOrDefault(generationId, List.of()), messages));
        return true;
    }
    @Override public List<Message> list(UUID generationId) { return List.copyOf(continuationByGeneration.getOrDefault(generationId, List.of())); }
    @Override public synchronized void complete(Conversation conversation, Generation generation) {
        Generation current = generationsById.get(generation.id());
        if (current == null || current.status() == GenerationStatus.CANCELLED || current.status() == GenerationStatus.COMPLETED) return;
        if (current.status() != GenerationStatus.RUNNING || !java.util.Objects.equals(current.claimToken(), generation.claimToken())) return;
        Message assistant = conversation.messages().stream().filter(message -> message.id().equals(generation.assistantMessageId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("completed generation references missing assistant message"));
        Conversation persisted = conversations.get(conversation.id());
        if (persisted == null) throw new IllegalStateException("conversation not found: " + conversation.id());
        java.util.ArrayList<Message> merged = new java.util.ArrayList<>(persisted.messages());
        if (merged.stream().noneMatch(message -> message.id().equals(assistant.id()))) merged.add(assistant);
        merged.sort(java.util.Comparator.comparing(Message::createdAt).thenComparing(Message::id));
        conversations.put(conversation.id(), Conversation.rehydrate(persisted.id(), persisted.userId(), persisted.createdAt(), merged));
        putGeneration(generation);
    }
    @Override public synchronized void fail(Generation generation) {
        Generation current = generationsById.get(generation.id());
        if (current == null || current.status() == GenerationStatus.CANCELLED || current.status() == GenerationStatus.FAILED) return;
        if (current.status() != GenerationStatus.RUNNING || !java.util.Objects.equals(current.claimToken(), generation.claimToken())) return;
        putGeneration(generation);
    }

    private boolean before(Conversation conversation, Cursor cursor) {
        int time = conversation.createdAt().compareTo(cursor.createdAt());
        return time < 0 || (time == 0 && conversation.id().toString().compareTo(cursor.conversationId().toString()) < 0);
    }
    private String encodeCursor(Metadata metadata) {
        String raw = metadata.createdAt().toEpochMilli() + ":" + metadata.conversationId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            if (separator <= 0) throw new IllegalArgumentException("invalid conversation cursor");
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator))),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid conversation cursor", exception);
        }
    }
    private List<Message> concat(List<Message> current, List<Message> added) {
        java.util.ArrayList<Message> result = new java.util.ArrayList<>(current.size() + added.size());
        result.addAll(current); result.addAll(added); return List.copyOf(result);
    }
    private void putGeneration(Generation generation) {
        generations.put(new TurnKey(generation.conversationId(), generation.idempotencyKey()), generation);
        generationsById.put(generation.id(), generation);
    }
    private Conversation copy(Conversation conversation) {
        return Conversation.rehydrate(conversation.id(), conversation.userId(), conversation.createdAt(), conversation.messages());
    }
    private record Cursor(Instant createdAt, UUID conversationId) { }
    private record TurnKey(UUID conversationId, String idempotencyKey) { }
}
