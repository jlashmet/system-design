package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.LongTermMemoryStore;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryLongTermMemoryStore implements LongTermMemoryStore {
    private final Map<UUID, Memory> memories = new ConcurrentHashMap<>();

    @Override
    public List<Memory> list(String userId, int limit) {
        return memories.values().stream()
                .filter(memory -> memory.userId().equals(userId))
                .sorted(Comparator.comparing(Memory::updatedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public void upsert(Memory memory) {
        memories.put(memory.id(), memory);
    }
}
